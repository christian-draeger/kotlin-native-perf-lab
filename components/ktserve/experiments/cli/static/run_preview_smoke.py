"""Compile and run a route fragment using the standalone CLI download."""

import os
from pathlib import Path
import platform
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time

from smoke import request


ROUTE = b'get("/health") { text("ok") }\n'


def verify(binary, directory):
    launcher = directory / "ktserve"
    shutil.copy2(binary, launcher)
    launcher.chmod(0o755)
    command = [launcher, "run", "-"]
    if platform.system() == "Linux" and platform.machine() in ("aarch64", "arm64"):
        result = subprocess.run(command, input=ROUTE, cwd=directory, capture_output=True, timeout=15)
        assert result.returncode != 0 and b"Linux ARM64" in result.stderr, result
        return

    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    env = os.environ | {
        "BIND_HOST": "127.0.0.1",
        "PORT": str(port),
        "XDG_CACHE_HOME": str(directory / "user cache"),
    }
    with tempfile.TemporaryFile(mode="w+") as log, subprocess.Popen(
        command, cwd=directory, env=env, stdin=subprocess.PIPE,
        stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
    ) as process:
        try:
            process.stdin.write(ROUTE)
            process.stdin.close()
            deadline = time.monotonic() + 360
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    status, headers, body = request(port, "/health")
                    break
                except ConnectionRefusedError:
                    time.sleep(0.15)
            else:
                raise AssertionError(f"The compiled app did not start (exit status {process.poll()})")
            assert (status, headers["Content-Type"], body) == (200, "text/plain; charset=UTF-8", b"ok")
            assert request(port, "/")[0] == 404
            executable = subprocess.check_output(["ps", "-p", str(process.pid), "-o", "comm="], text=True).strip()
            assert Path(executable).name.startswith("ktserve-app-"), executable
        except Exception:
            log.seek(0)
            print(log.read(), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="ktserve run smoke ") as directory:
        verify(Path(sys.argv[1]).resolve(), Path(directory))
    print("Standalone CLI run preview passed")
