"""Exercise the standalone CLI's three Kotlin input modes."""

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


def free_port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def verify_app(command, directory, port, env, routes, snippet=None):
    stdin = subprocess.PIPE if snippet is not None else subprocess.DEVNULL
    with tempfile.TemporaryFile(mode="w+") as log, subprocess.Popen(
        command, cwd=directory, env=env, stdin=stdin,
        stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
    ) as process:
        try:
            if snippet is not None:
                process.stdin.write(snippet)
                process.stdin.close()
            deadline = time.monotonic() + 360
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    first = request(port, routes[0][0])
                    break
                except ConnectionRefusedError:
                    time.sleep(0.15)
            else:
                raise AssertionError(f"The compiled app did not start (exit status {process.poll()})")
            responses = [first] + [request(port, route) for route, _ in routes[1:]]
            for (route, expected), (status, headers, body) in zip(routes, responses):
                assert (status, headers["Content-Type"], body) == (200, "text/plain; charset=UTF-8", expected), route
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


def verify(binary, directory):
    launcher = directory / "ktserve"
    shutil.copy2(binary, launcher)
    launcher.chmod(0o755)
    command = [launcher, "run"]
    if platform.system() == "Linux" and platform.machine() in ("aarch64", "arm64"):
        for args in (["-"], ["--app", "-"]):
            result = subprocess.run(command + args, input=ROUTE, cwd=directory, capture_output=True, timeout=15)
            assert result.returncode != 0 and b"Linux ARM64" in result.stderr, result
        return

    env = os.environ | {"XDG_CACHE_HOME": str(directory / "user cache")}
    port = free_port()
    verify_app(
        command + ["-"], directory, port, env | {"BIND_HOST": "127.0.0.1", "PORT": str(port)},
        [("/health", b"ok")], ROUTE,
    )

    port = free_port()
    app = f'''serve(port = {port}, host = "127.0.0.1", limits = ServerLimits(responseBodyBytes = 128)) {{
    get("/health") {{ text("ok") }}
    get("/ready") {{ text("ready") }}
}}
'''.encode()
    verify_app(
        command + ["--app", "-"], directory, port, env | {"PORT": "1"},
        [("/health", b"ok"), ("/ready", b"ready")], app,
    )

    source = directory / "sample App.kt"
    source.write_text('''import codes.draeger.ktserve.*

fun main(args: Array<String>) {
    serve(port = args[0].toInt()) {
        get("/health") { text(args[1]) }
    }
}
''', encoding="utf-8")
    port = free_port()
    verify_app(
        command + [source.name, str(port), "file app"], directory, port, env,
        [("/health", b"file app")],
    )


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="ktserve run smoke ") as directory:
        verify(Path(sys.argv[1]).resolve(), Path(directory))
    print("Standalone CLI run preview passed")
