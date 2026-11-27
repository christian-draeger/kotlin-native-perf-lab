#!/usr/bin/env python3
"""Exercise the typed app through a Native CLI binary with file and stdin inputs."""

from http.client import HTTPConnection
from pathlib import Path
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time


APP = Path(__file__).with_name("TypedApp.kt").resolve()


def request(port, method, path, body=None):
    connection = HTTPConnection("127.0.0.1", port, timeout=3)
    try:
        headers = {"Content-Type": "application/json"} if body is not None else {}
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        return response.status, response.getheader("Content-Type"), response.read()
    finally:
        connection.close()


def verify(cli, directory, use_stdin):
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    source = ["--source", "-"] if use_stdin else [str(APP)]
    command = [cli, "run", "--extension", "validation", "--extension", "openapi", *source, str(port)]
    env = os.environ | {"XDG_CACHE_HOME": str(directory / "CLI cache")}
    with APP.open("rb") as input_file, tempfile.TemporaryFile(mode="w+") as log, subprocess.Popen(
        command, cwd=directory, env=env, stdin=input_file if use_stdin else subprocess.DEVNULL,
        stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
    ) as process:
        try:
            deadline = time.monotonic() + 360
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    assert request(port, "GET", "/api/health") == (200, "text/plain; charset=UTF-8", b"ok")
                    break
                except ConnectionRefusedError:
                    time.sleep(0.15)
            else:
                raise AssertionError(f"The compiled app did not start (exit status {process.poll()})")
            assert request(port, "GET", "/api/users/42") == (
                200, "application/json", b'{"id":42,"name":"Ada"}',
            )
            assert request(port, "POST", "/api/users", b'{"name":"Ada"}') == (
                201, "application/json", b'{"id":42,"name":"Ada"}',
            )
            status, media_type, payload = request(port, "POST", "/api/users", b'{"name":"A"}')
            assert (status, media_type) == (422, "application/problem+json")
            assert json.loads(payload)["errors"][0]["pointer"] == "/name"
            status, media_type, payload = request(port, "GET", "/openapi.json")
            assert (status, media_type) == (200, "application/json")
            post = json.loads(payload)["paths"]["/api/users"]["post"]
            assert "application/json" in post["responses"]["201"]["content"]
            schema = post["requestBody"]["content"]["application/json"]["schema"]
            assert any(branch.get("properties", {}).get("name", {}).get("minLength") == 2
                       for branch in schema.get("allOf", [schema])), schema
        except BaseException:
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
    cli = Path(sys.argv[1]).resolve()
    with tempfile.TemporaryDirectory(prefix="ktserve typed CLI ") as directory:
        for use_stdin in (False, True):
            verify(cli, Path(directory), use_stdin)
    print("Typed Native CLI smoke passed for App.kt and stdin")
