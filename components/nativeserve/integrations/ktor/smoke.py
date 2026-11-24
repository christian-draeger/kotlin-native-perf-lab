#!/usr/bin/env python3
"""Run the same Ktor application on both Linux Native engines over real HTTP sockets."""

from http.client import HTTPConnection
import json
import socket
import subprocess
import sys
import time


def request(port, path, body=None):
    connection = HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        connection.request("GET" if body is None else "POST", path, body=body, headers={
            "Accept": "application/json, */*",
            "Connection": "close",
        })
        response = connection.getresponse()
        assert response.getheader("X-Example") == "same-ktor-application"
        return response.status, response.read()
    finally:
        connection.close()


def wait_until(condition, message):
    until = time.monotonic() + 5
    while True:
        try:
            if condition():
                return
        except ConnectionRefusedError:
            pass
        assert time.monotonic() < until, message
        time.sleep(0.02)


def smoke(binary, engine):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    with subprocess.Popen([binary, str(port), engine]) as server:
        try:
            wait_until(lambda: request(port, "/health") == (200, b"ok"), f"{engine} failed to bind")
            status, body = request(port, "/json")
            assert status == 200
            assert json.loads(body) == {"id": 42, "name": "benchmark-user", "active": True}
            assert request(port, "/upload", b"x" * (24 * 1024)) == (200, b"24576")
            assert request(port, "/download") == (200, b"s" * (16 * 1024) + b"t" * (16 * 1024 + 3))
            assert request(port, "/missing")[0] == 404

            if engine == "nativeserve":
                with socket.create_connection(("127.0.0.1", port), timeout=5) as client:
                    client.sendall(b"GET /cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    wait_until(lambda: request(port, "/suspended") == (200, b"1"), "Ktor handler did not suspend")
                wait_until(lambda: request(port, "/suspended") == (200, b"0"), "Disconnect did not cancel the call")

            assert request(port, "/stop") == (200, b"ok")
            assert server.wait(timeout=5) == 0
        finally:
            if server.poll() is None:
                server.terminate()
            server.wait(timeout=5)
    print(f"{engine}: routing, typed JSON, application headers, request and response streaming, shutdown")


if __name__ == "__main__":
    for selected in ("nativeserve", "cio"):
        smoke(sys.argv[1], selected)
