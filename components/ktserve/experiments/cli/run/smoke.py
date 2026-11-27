#!/usr/bin/env python3
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import urlopen


HERE = Path(__file__).resolve().parent


def request(port, path):
    try:
        with urlopen(f"http://127.0.0.1:{port}{path}", timeout=1) as response:
            return response.status, response.read()
    except HTTPError as response:
        with response:
            return response.code, response.read()


def wait_for_app(process, port):
    deadline = time.monotonic() + 240
    while process.poll() is None and time.monotonic() < deadline:
        try:
            return request(port, "/users/123")
        except OSError:
            time.sleep(0.2)
    raise AssertionError(f"The compiled app did not become ready (exit status {process.poll()})")


def main():
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        source = directory / "my sample App.kt"
        source.write_bytes((HERE / "App.kt").read_bytes())
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        command = [HERE.parent / "ktserve", "run", source.name, str(port)]
        with tempfile.TemporaryFile(mode="w+") as log:
            process = subprocess.Popen(
                command, cwd=directory, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
            )
            try:
                assert wait_for_app(process, port) == (200, b"user-123")
                assert request(port, "/users/42") == (200, b"user-42")
                assert request(port, "/users/not-a-number")[0] == 400
                executable = subprocess.check_output(
                    ["ps", "-p", str(process.pid), "-o", "comm="], text=True
                ).strip()
                assert Path(executable).name.startswith("ktserve-app"), executable
            except BaseException:
                log.seek(0)
                print(log.read(), end="")
                raise
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()

        source.write_text("fun main() { val broken = }\n", encoding="utf-8")
        failed = subprocess.run(command, cwd=directory, capture_output=True, text=True, timeout=240)
        assert failed.returncode != 0, failed.stdout
        assert "App.kt" in failed.stderr and "compileKotlinNative" in failed.stdout, failed.stderr
    print("run smoke passed")


if __name__ == "__main__":
    main()
