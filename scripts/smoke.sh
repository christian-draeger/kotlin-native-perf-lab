#!/usr/bin/env bash
set -euo pipefail
python3 - "${1:-http://127.0.0.1:8080}" <<'PY_CHECK'
import http.client
import json
import os
import sys
from urllib.parse import urlsplit

base = urlsplit(sys.argv[1])
connection_type = {"http": http.client.HTTPConnection, "https": http.client.HTTPSConnection}[base.scheme]
connection = connection_type(base.netloc, timeout=5)
connection.connect()
socket = connection.sock
cases = [
    ("/health", 200, "text/plain", b"ok"),
    ("/json", 200, "application/json", b'{"id":42,"name":"benchmark-user","active":true}'),
    ("/users/123", 200, "application/json", b'{"id":123,"name":"benchmark-user","active":true}'),
]
suite = os.environ.get("BENCHMARK_SUITE")
if suite in ("application", "application-mix"):
    for catalog_id in (123, 987):
        items = [
            {"sku": f"{catalog_id}-{index}", "name": f"Café item {index}",
             "description": "Roasted in München and packed for delivery.",
             "stock": {"available": index > 0, "units": index * 3}}
            for index in range(32)
        ]
        body = json.dumps({"id": catalog_id, "items": items}, ensure_ascii=False, separators=(",", ":")).encode()
        cases.append((f"/catalog/{catalog_id}", 200, "application/json", body))
    cases.append(("/catalog/invalid", 400, None, None))
if suite == "http2-stream":
    cases.append(("/stream", 200, "application/octet-stream", b"x" * (80 * 1024)))
cases.append(("/users/invalid", 400, None, None))

def check_application_mix():
    connection.request("GET", base.path.rstrip("/") + "/wait")
    with connection.getresponse() as response:
        assert response.version == 11 and response.status == 200 and response.read() == b"ready"
        assert response.headers.get_content_type() == "text/plain"
    connection.request("GET", base.path.rstrip("/") + "/download")
    with connection.getresponse() as response:
        assert response.version == 11 and response.status == 200 and response.chunked
        assert response.headers.get_content_type() == "application/octet-stream"
        assert response.read(16 * 1024) == b"s" * (16 * 1024)
        assert response.read() == b"t" * (16 * 1024 + 3)
    assert connection.sock is socket, "The streamed response closed the keep-alive connection"

    uploads = [(b"x" * size, 200) for size in (1024, 24 * 1024, 64 * 1024)]
    uploads += [(b"x" * 1023 + b"y", 400), (b"x" * (64 * 1024 + 1), 413)]
    for payload, status in uploads:
        connection.request("POST", base.path.rstrip("/") + "/upload", payload,
                           {"Content-Type": "application/octet-stream"})
        with connection.getresponse() as response:
            body = response.read()
            assert response.version == 11 and response.status == status, (response.status, len(payload))
            if status == 200:
                assert response.headers.get_content_type() == "text/plain" and body == str(len(payload)).encode()
                assert connection.sock is socket, "The upload closed the keep-alive connection"
    print("Application mix passed (suspension, streamed bytes, bounded and validated uploads)")

try:
    for path, expected_status, media_type, expected_body in cases:
        connection.request("GET", base.path.rstrip("/") + path)
        with connection.getresponse() as response:
            body = response.read()
            content_type = response.headers.get("Content-Type", "")
            content_length = response.headers.get("Content-Length")
            transfer_encoding = response.headers.get("Transfer-Encoding")
            assert response.version == 11, (path, response.version)
            assert response.status == expected_status, (path, response.status, body)
            if media_type:
                assert response.headers.get_content_type() == media_type, (path, content_type)
                charset = response.headers.get_content_charset()
                assert charset in (None, "utf-8"), (path, charset)
                assert not response.headers.get("Content-Encoding"), (path, dict(response.headers))
                assert (content_length, transfer_encoding) in (
                    (str(len(body)), None), (None, "chunked")
                ), (path, dict(response.headers))
                assert connection.sock is socket, (path, "HTTP connection closed")
                if media_type == "application/json":
                    payload = json.loads(body)
                    assert payload == json.loads(expected_body), (path, payload)
                assert body == expected_body, (path, body)
            print(f"{path} HTTP/1.1 {response.status} Content-Type={content_type!r} "
                  f"Content-Length={content_length!r} Transfer-Encoding={transfer_encoding!r} "
                  f"body_bytes={len(body)}")
    if suite == "application-mix":
        check_application_mix()
finally:
    connection.close()
PY_CHECK
