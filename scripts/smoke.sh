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
if os.environ.get("BENCHMARK_SUITE") == "application":
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
cases.append(("/users/invalid", 400, None, None))

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
finally:
    connection.close()
PY_CHECK
