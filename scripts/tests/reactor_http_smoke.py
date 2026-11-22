#!/usr/bin/env python3
"""Exercise fragmented and pipelined requests against the reactor release binary."""
from concurrent.futures import ThreadPoolExecutor
import socket
import sys


def response(stream):
    status_line = stream.readline().decode("ascii").strip()
    assert status_line.startswith("HTTP/1.1 "), status_line
    headers = {}
    while True:
        line = stream.readline()
        if line == b"\r\n":
            break
        assert line, "Response closed before the headers completed"
        name, value = line.decode("ascii").split(":", 1)
        headers[name.lower()] = value.strip()
    size = int(headers["content-length"])
    body = stream.read(size)
    assert len(body) == size, (size, body)
    return int(status_line.split()[1]), headers, body


def connect(port):
    return socket.create_connection(("127.0.0.1", port), timeout=5)


def main(port):
    with connect(port) as conn, conn.makefile("rb") as stream:
        conn.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r")
        conn.sendall(b"\nGET /users/123 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        first = response(stream)
        second = response(stream)
        assert first[0] == 200 and first[2] == b"ok", first
        assert second[0] == 200 and second[2] == b'{"id":123,"name":"benchmark-user","active":true}', second
        assert second[1]["connection"] == "close"
        assert stream.read(1) == b""

    with connect(port) as conn, conn.makefile("rb") as stream:
        prefix = b"GET /health HTTP/1.1\r\nHost: localhost\r\nX-Padding: "
        conn.sendall(prefix + b"a" * (16384 - len(prefix)))
        status, headers, _ = response(stream)
        assert status == 431 and headers["connection"] == "close", (status, headers)

    def check_client(_):
        with connect(port) as conn, conn.makefile("rb") as stream:
            conn.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            status, _, body = response(stream)
            assert status == 200 and body == b"ok"

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(check_client, range(24)))
    print("Reactor stream smoke passed")


if __name__ == "__main__":
    main(int(sys.argv[1]))
