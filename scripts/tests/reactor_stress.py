#!/usr/bin/env python3
"""Exercise the reactor's resource bounds with an isolated release fixture."""

from contextlib import ExitStack
from http.client import HTTPConnection, IncompleteRead
from pathlib import Path
import socket
import subprocess
import sys
import time

from reactor_http_smoke import response

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
    windows = sys.platform == "win32"

    def open_fds():
        if sys.platform == "darwin":
            fields = subprocess.check_output(["lsof", "-nP", "-p", str(pid), "-Ff"], text=True)
            return sum(line.startswith("f") and line[1:].isdigit() for line in fields.splitlines())
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
    initial_fds = None if windows else open_fds()

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
            conn = clients.enter_context(socket.create_connection(("127.0.0.1", port), timeout=5))
            if windows:
                conn.sendall(request("/health"))
                with conn.makefile("rb") as stream:
                    assert response(stream)[2] == b"ok"
        if initial_fds is not None:
            wait_until(lambda: open_fds() == initial_fds + CONNECTIONS, "Fixture never reached its connection limit")
        with socket.create_connection(("127.0.0.1", port), timeout=1) as overflow:
            assert_closed(overflow)
        if initial_fds is not None:
            assert open_fds() == initial_fds + CONNECTIONS

    if initial_fds is not None:
        wait_until(lambda: open_fds() <= initial_fds, "Disconnected clients leaked file descriptors")
    final_heap = None

    def heap_reclaimed():
        nonlocal final_heap
        try:
            final_heap = read_counter("/heap")
        except ConnectionResetError:
            return False
        return final_heap <= initial_heap + MIB

    wait_until(heap_reclaimed, f"Live heap did not return within {MIB} bytes of {initial_heap}")
    print(f"Reactor resource checks passed (connection cap, live heap {initial_heap} -> {final_heap} bytes)")


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


def test_request_bodies(port):
    head = b"POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 7\r\n\r\n"

    def active():
        return fetch(port, "/open-requests")

    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(head + b"abc")
        wait_until(lambda: active() == (200, b"1"), "Request sink never opened")
        conn.sendall(b"defg" + request("/health"))
        with conn.makefile("rb") as stream:
            assert response(stream)[2] == b"7"
            assert response(stream)[2] == b"ok"
        assert active() == (200, b"0")

    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(head + b"abc")
        wait_until(lambda: active() == (200, b"1"), "Request sink never opened")
    wait_until(lambda: active() == (200, b"0"), "Disconnected request leaked its body sink")

    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(head + b"abc")
        wait_until(lambda: active() == (200, b"1"), "Request sink never opened")
        assert_closed(conn)
        assert active() == (200, b"0")

    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(b"POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n3\r\na")
        wait_until(lambda: active() == (200, b"1"), "Chunked request sink never opened")
        conn.sendall(b"bc\r\n2\r\nde\r\n0\r\nX-Meta: ok\r\n\r\n" + request("/health"))
        with conn.makefile("rb") as stream:
            assert response(stream)[2] == b"5"
            assert response(stream)[2] == b"ok"
        assert active() == (200, b"0")
    print("Request streaming checks passed (pipeline, disconnect, deadline)")


def test_suspended_handlers(port):
    def active(count):
        return fetch(port, "/open-handlers") == (200, str(count).encode("ascii"))

    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(request("/suspend/resume"))
        wait_until(lambda: active(1), "Handler never suspended")
        conn.sendall(request("/health"))
        assert fetch(port, "/complete-handler") == (200, b"ok")
        with conn.makefile("rb") as stream:
            assert response(stream)[2] == b"resumed"
            assert response(stream)[2] == b"ok"
    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.sendall(request("/suspend/cancel"))
        wait_until(lambda: active(1), "Handler never entered before disconnect")
    wait_until(lambda: active(0), "Disconnect did not cancel the suspended handler", seconds=2)
    print("Suspending GET checks passed (concurrency, pipeline, disconnect)")


def ephemeral_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def wait_for_fixture(port, server):
    def ready():
        assert server.poll() is None, "Fixture exited at startup"
        try:
            return fetch(port, "/health") == (200, b"ok")
        except ConnectionRefusedError:
            return False

    wait_until(ready, "Fixture never became ready")


def test_application(binary):
    port = ephemeral_port()
    with subprocess.Popen([binary, "--application", str(port)]) as server:
        try:
            wait_for_fixture(port, server)
            with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
                conn.sendall(b"PATCH /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n")
                conn.sendall(b"6000\r\n" + b"a" * (24 * 1024) + b"\r\n0\r\n\r\n")
                conn.sendall(request("/health"))
                assert fetch(port, "/health") == (200, b"ok")
                with conn.makefile("rb") as stream:
                    assert response(stream)[2] == b"PATCH:24576"
                    assert response(stream)[2] == b"ok"
            assert fetch(port, "/download") == (200, b"x" * (16 * 1024) + b"y" * (8 * 1024 + 3))
            assert fetch(port, "/stop") == (200, b"ok")
            assert server.wait(timeout=5) == 0
        finally:
            if server.poll() is None:
                server.terminate()
            server.wait(timeout=5)
    print("Suspending application checks passed (request backpressure, response streaming, pipeline, drain)")


def main(binary):
    subprocess.run([binary, "--transport", str(ephemeral_port())], check=True, timeout=10)
    port = ephemeral_port()
    with subprocess.Popen([binary, str(port)]) as server:
        try:
            wait_for_fixture(port, server)
            test_limits(port, server.pid)
            test_request_bodies(port)
            test_suspended_handlers(port)
            test_streams(port, server)
        finally:
            if server.poll() is None:
                server.terminate()
            server.wait(timeout=5)
    test_application(binary)


if __name__ == "__main__":
    main(sys.argv[1])
