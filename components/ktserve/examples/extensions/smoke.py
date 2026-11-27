#!/usr/bin/env python3
"""Exercise the published extension variants against the same HTTP requests."""

import http.client
import json
import socket
import subprocess
import sys
import time


def smoke(command):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]

    server = subprocess.Popen([*command, str(port)])
    client = http.client.HTTPConnection("127.0.0.1", port, timeout=3)

    def request(method, path, headers=None, body=None):
        client.request(method, path, body=body, headers=headers or {})
        response = client.getresponse()
        return response.status, response.headers, response.read()

    try:
        for attempt in range(80):
            try:
                client.connect()
                break
            except ConnectionRefusedError:
                if server.poll() is not None or attempt == 79:
                    raise
                time.sleep(0.1)

        origin = "https://app.example"
        preflight = {
            "Origin": origin,
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Authorization, X-Region",
        }
        status, headers, body = request("OPTIONS", "/health", preflight)
        assert (status, body) == (204, b"")
        assert headers.get_all("Access-Control-Allow-Origin") == [origin]
        assert headers["Access-Control-Allow-Credentials"] == "true"
        assert "access-control-request-method" in headers["Vary"].lower()
        status, headers, _ = request("OPTIONS", "/health", {**preflight, "Origin": "https://denied.example"})
        assert status == 403 and "Access-Control-Allow-Origin" not in headers
        status, headers, _ = request("OPTIONS", "/health", {**preflight, "Access-Control-Request-Headers": "X-Denied"})
        assert status == 403 and "Access-Control-Allow-Origin" not in headers

        status, headers, body = request("GET", "/health", {"Origin": origin})
        assert (status, body) == (400, b"Missing region")
        assert headers.get_all("Access-Control-Allow-Origin") == [origin]
        assert "X-Region" not in headers

        context = {"Origin": origin, "X-Region": "EU"}
        status, headers, body = request("GET", "/health", context)
        assert (status, body) == (401, b"Bearer credential required")
        assert headers["X-Region"] == "EU" and headers["Access-Control-Allow-Origin"] == origin
        status, headers, body = request("GET", "/health", {**context, "Authorization": "Bearer example"})
        assert (status, body) == (200, b"ok")
        assert headers["X-Region"] == "EU" and headers["Access-Control-Allow-Credentials"] == "true"
        status, headers, _ = request("GET", "/health", {**context, "Origin": "https://denied.example"})
        assert status == 401 and "Access-Control-Allow-Origin" not in headers
        status, _, body = request("OPTIONS", "/health", context)
        assert (status, body) == (200, b"ordinary OPTIONS")

        typed = {**context, "Content-Type": "application/json"}
        status, headers, body = request("POST", "/api/users", typed, b'{"name":"A"}')
        assert status == 422 and headers["Content-Type"] == "application/problem+json"
        assert json.loads(body)["errors"][0]["pointer"] == "/name"
        status, _, body = request("POST", "/api/users", typed, b'{"name":"Ada"}')
        assert status == 201 and json.loads(body) == {"name": "Ada"}
        status, _, body = request("GET", "/openapi.json", context)
        assert status == 200
        schema = json.loads(body)["paths"]["/api/users"]["post"]["requestBody"]["content"]["application/json"]["schema"]
        assert any(branch.get("properties", {}).get("name", {}).get("minLength") == 2
                   for branch in schema.get("allOf", [schema])), schema
    finally:
        client.close()
        server.terminate()
        try:
            server.wait(timeout=8)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()


if __name__ == "__main__":
    smoke(sys.argv[1:])
    print("Independent extension consumer smoke passed")
