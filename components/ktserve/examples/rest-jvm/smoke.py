"""Exercise the REST consumer at its loopback origin with a test identity assertion."""

import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time


def request(port, method, path, body=None, identity=None):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if identity is not None:
        headers["X-KtServe-Identity"] = identity
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def main(binary):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]

    with subprocess.Popen(
        [binary, str(port)],
        env=os.environ | {"KTSERVE_REST_USER": "fixture-writer"},
    ) as server:
        try:
            deadline = time.monotonic() + 10
            while True:
                try:
                    assert request(port, "GET", "/health") == (200, b"ok")
                    break
                except ConnectionRefusedError:
                    if server.poll() is not None or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.05)

            body = b'{"message":"hello"}'
            assert request(port, "PUT", "/items/42", body) == (403, b"Forbidden")
            status, data = request(port, "PUT", "/items/42", body, "fixture-writer")
            assert status == 200 and json.loads(data) == {"id": 42, "message": "hello"}
            assert request(port, "PUT", "/items/42", b'{"message":', "fixture-writer")[0] == 400
            assert request(port, "PUT", "/items/42", b"x" * 4097, "fixture-writer")[0] == 413
        finally:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                raise

    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))
    print("REST consumer verified authorization, valid JSON, 400, 413, and listener release")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
