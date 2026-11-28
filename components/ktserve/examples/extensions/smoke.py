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

        origin = "http://localhost:5173"
        preflight = {
            "Origin": origin,
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Authorization, X-Workspace",
        }
        status, _, body = request("GET", "/health")
        assert (status, body) == (200, b"ok")
        status, headers, body = request("OPTIONS", "/api/tasks", preflight)
        assert (status, body) == (204, b"")
        assert headers.get_all("Access-Control-Allow-Origin") == [origin]
        assert headers["Access-Control-Allow-Credentials"] == "true"
        assert "access-control-request-method" in headers["Vary"].lower()
        status, headers, _ = request("OPTIONS", "/api/tasks", {**preflight, "Origin": "https://denied.example"})
        assert status == 403 and "Access-Control-Allow-Origin" not in headers
        status, headers, _ = request("OPTIONS", "/api/tasks", {**preflight, "Access-Control-Request-Headers": "X-Denied"})
        assert status == 403 and "Access-Control-Allow-Origin" not in headers

        status, headers, body = request("GET", "/api/tasks", {"Origin": origin})
        assert (status, body) == (401, b"Invalid demo token")
        assert headers["WWW-Authenticate"] == "Bearer"
        assert headers.get_all("Access-Control-Allow-Origin") == [origin]
        assert "X-Workspace" not in headers

        authorized = {"Origin": origin, "Authorization": "Bearer taskboard-demo"}
        status, headers, body = request("GET", "/api/tasks", authorized)
        assert (status, body) == (400, b"Select a workspace")
        assert headers["Access-Control-Allow-Origin"] == origin
        context = {**authorized, "X-Workspace": "design"}
        status, headers, body = request("GET", "/api/tasks", context)
        assert (status, json.loads(body)) == (200, [])
        assert headers["X-Workspace"] == "design" and headers["Access-Control-Allow-Credentials"] == "true"
        status, headers, _ = request("GET", "/api/tasks", {**context, "Origin": "https://denied.example"})
        assert status == 200 and "Access-Control-Allow-Origin" not in headers
        status, _, body = request("OPTIONS", "/api/tasks", context)
        assert (status, body) == (200, b"ordinary OPTIONS")

        typed = {**context, "Content-Type": "application/json"}
        status, headers, body = request("POST", "/api/tasks", typed, b'{"title":"A"}')
        assert status == 422 and headers["Content-Type"] == "application/problem+json"
        assert json.loads(body)["errors"][0]["pointer"] == "/title"
        status, _, body = request("POST", "/api/tasks", typed, b'{"title":"Review design"}')
        created = {"id": 1, "title": "Review design", "done": False}
        assert status == 201 and json.loads(body) == created
        status, _, body = request("GET", "/api/tasks", context)
        assert (status, json.loads(body)) == (200, [created])
        status, _, body = request("GET", "/api/tasks", {**context, "X-Workspace": "other"})
        assert (status, json.loads(body)) == (200, [])
        status, _, _ = request("PATCH", "/api/tasks/1", {**typed, "X-Workspace": "other"}, b'{"done":true}')
        assert status == 404
        status, _, body = request("PATCH", "/api/tasks/1", typed, b'{"done":true}')
        assert status == 200 and json.loads(body) == {**created, "done": True}
        status, _, body = request("GET", "/openapi.json")
        assert status == 200
        schema = json.loads(body)["paths"]["/api/tasks"]["post"]["requestBody"]["content"]["application/json"]["schema"]
        assert any(branch.get("properties", {}).get("title", {}).get("minLength") == 3
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
