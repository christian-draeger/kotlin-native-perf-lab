import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from tempfile import TemporaryDirectory

from smoke import check_access_log, request


BODY_LIMIT = 1024 * 1024


def check_rejected(binary):
    for args, body, code, message in (
        ([], b"", 1, b"stdin is empty"),
        ([], b" \t\n", 1, b"stdin is empty"),
        ([], b'"\xff"', 1, b"stdin must be UTF-8"),
        ([], b" " * (BODY_LIMIT + 1), 1, b"stdin exceeds 1048576 bytes"),
        (["."], b"{}", 2, b"Usage: ktserve serve"),
        (["--mount", "/extra", "."], b"{}", 2, b"Usage: ktserve serve"),
    ):
        result = subprocess.run([binary, "serve", "-", *args], input=body, capture_output=True, timeout=5)
        assert result.returncode == code and message in result.stderr, result
        assert b"starting at" not in result.stdout


def check_served(binary, fixture):
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    payload = '{ "status": "ok", "message": "läuft" }\n'.encode()
    log_path = fixture / "server.log"
    with log_path.open("w+") as log, subprocess.Popen(
        [binary, "serve", "-", "--access-log"],
        stdin=subprocess.PIPE,
        stdout=log,
        stderr=log,
        env=os.environ | {"BIND_HOST": "127.0.0.1", "PORT": str(port)},
    ) as server:
        try:
            server.stdin.write(payload)
            server.stdin.close()
            for _ in range(100):
                assert server.poll() is None, "The stdin server exited during startup"
                try:
                    status, headers, body = request(port, "/")
                    break
                except ConnectionRefusedError:
                    time.sleep(0.05)
            else:
                raise AssertionError("The stdin server did not start")
            assert (status, headers["Content-Type"], body) == (200, "application/json", payload)
            status, headers, body = request(port, "/", "HEAD")
            assert (status, headers["Content-Length"], body) == (200, str(len(payload)), b"")
            assert request(port, "/somewhere-else")[0] == 404
            assert request(port, "/", "POST")[0] == 405
            check_access_log(log_path)
        except Exception:
            log.seek(0)
            print(log.read(), file=sys.stderr, end="")
            raise
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    server.kill()
                    raise


if __name__ == "__main__":
    binary = Path(sys.argv[1]).resolve()
    check_rejected(binary)
    with TemporaryDirectory() as directory:
        check_served(binary, Path(directory))
    print("CLI stdin smoke passed")
