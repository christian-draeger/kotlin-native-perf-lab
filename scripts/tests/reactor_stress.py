#!/usr/bin/env python3
"""Exercise the reactor's resource bounds with an isolated release fixture."""

from contextlib import ExitStack
from http.client import HTTPConnection, IncompleteRead
from pathlib import Path
import socket
import subprocess
import sys
import time

BODY_BYTES = 8 * 1024 * 1024
STREAM_BYTES = BODY_BYTES - 3
MIB = 1024 * 1024
CONNECTIONS = 8


def wait_until(condition, message, seconds=5):
    deadline = time.monotonic() + seconds
    while not condition():
        assert time.monotonic() < deadline, message
        time.sleep(0.02)


def fetch(port, route):
    conn = HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        conn.request("GET", route, headers={"Connection": "close"})
        response = conn.getresponse()
        return response.status, response.read()
    finally:
        conn.close()


def request(route):
    return f"GET {route} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode("ascii")


def pending_client(port, route="/large", following="/large"):
    conn = socket.socket()
    try:
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        conn.settimeout(5)
        conn.connect(("127.0.0.1", port))
        conn.sendall(request(route) + request(following))
        assert conn.recv(1, socket.MSG_PEEK) == b"H"
        return conn
    except BaseException:
        conn.close()
        raise


def assert_closed(conn):
    try:
        assert not conn.recv(1)
    except ConnectionResetError:
        pass


def test_limits(port, pid):
    descriptors = Path(f"/proc/{pid}/fd")

    def open_fds():
        return len(list(descriptors.iterdir()))

    def read_counter(path):
        status, body = fetch(port, path)
        assert status == 200
        return int(body)

    assert fetch(port, "/health") == (200, b"ok")
    assert fetch(port, "/too-large") == (500, b"")
    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(b"GET /health HTTP/1.1\r\nHost: local")
        started = time.monotonic()
        assert_closed(conn)
        assert time.monotonic() - started < 2, "Partial head exceeded its deadline"
    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        assert_closed(conn)
    initial_fds = open_fds()

    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(request("/large"))
        with conn.makefile("rb") as body:
            line = body.readline()
            assert line.startswith(b"HTTP/1.1 200 ")
            while (line := body.readline()) != b"\r\n":
                assert line
            for _ in range(BODY_BYTES // 65536):
                assert body.read(65536) == b"x" * 65536

    initial_heap = read_counter("/heap")
    initial_large_requests = read_counter("/large-count")
    for _ in range(10):
        with ExitStack() as clients:
            for _ in range(4):
                clients.enter_context(pending_client(port))
            initial_large_requests += 4
            assert read_counter("/large-count") == initial_large_requests, "Dispatched a second request while output was blocked"
            assert fetch(port, "/health") == (200, b"ok")
        for _ in range(100):
            assert fetch(port, "/health") == (200, b"ok")

    with ExitStack() as clients:
        for _ in range(CONNECTIONS):
            clients.enter_context(socket.create_connection(("127.0.0.1", port), timeout=5))
        wait_until(lambda: open_fds() == initial_fds + CONNECTIONS, "Fixture never reached its connection limit")
        with socket.create_connection(("127.0.0.1", port), timeout=1) as overflow:
            assert_closed(overflow)
        assert open_fds() == initial_fds + CONNECTIONS

    wait_until(lambda: open_fds() <= initial_fds, "Disconnected clients leaked file descriptors")
    final_heap = None

    def heap_reclaimed():
        nonlocal final_heap
        final_heap = read_counter("/heap")
        return final_heap <= initial_heap + MIB

    wait_until(heap_reclaimed, f"Live heap did not return within {MIB} bytes of {initial_heap}")
    print(f"Reactor resource checks passed (fds={open_fds()}, live heap {initial_heap} -> {final_heap} bytes)")


def test_streams(port, server):
    assert fetch(port, "/chunks") == (200, b"s" * (32 * 1024 + 3))
    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(request("/stream") + request("/health"))
        with conn.makefile("rb") as response:
            assert response.readline().startswith(b"HTTP/1.1 200 ")
            headers = []
            while (line := response.readline()) != b"\r\n":
                assert line
                headers.append(line)
            assert f"Content-Length: {STREAM_BYTES}\r\n".encode("ascii") in headers
            for start in range(0, STREAM_BYTES, 65536):
                length = min(STREAM_BYTES - start, 65536)
                assert response.read(length) == b"s" * length
            assert response.readline().startswith(b"HTTP/1.1 200 ")
            while (line := response.readline()) != b"\r\n":
                assert line
            assert response.read(2) == b"ok"

    try:
        fetch(port, "/truncated-stream")
    except IncompleteRead as error:
        assert error.partial == b"s" and error.expected == STREAM_BYTES - 1
    else:
        raise AssertionError("Premature end of stream did not close the connection")

    def active_streams(count):
        return fetch(port, "/open-streams") == (200, str(count).encode("ascii"))

    assert active_streams(0)
    with pending_client(port, "/stream", "/health"):
        assert active_streams(1)
        assert fetch(port, "/health") == (200, b"ok")
    wait_until(lambda: active_streams(0), "Disconnected stream leaked its source")

    with pending_client(port, "/stream", "/health"):
        assert active_streams(1)
        wait_until(lambda: active_streams(0), "Idle stream leaked its source")

    with pending_client(port, "/stream", "/health"):
        assert active_streams(1)
        assert fetch(port, "/stop") == (200, b"ok")
        assert server.wait(timeout=5) == 0, "Shutdown did not release the stalled stream"
    print("Response streaming checks passed (pipeline, truncation, disconnect, idle, drain)")


def main(binary):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    subprocess.run([binary, "--epoll", str(port)], check=True, timeout=10)
    with subprocess.Popen([binary, str(port)]) as server:
        try:
            def ready():
                assert server.poll() is None, "Fixture exited at startup"
                try:
                    return fetch(port, "/health") == (200, b"ok")
                except ConnectionRefusedError:
                    return False

            wait_until(ready, "Fixture never became ready")
            test_limits(port, server.pid)
            test_streams(port, server)
        finally:
            if server.poll() is None:
                server.terminate()
            server.wait(timeout=5)


if __name__ == "__main__":
    main(sys.argv[1])
