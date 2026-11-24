#!/usr/bin/env python3
"""Exercise the example with the real CPU service and a stalled upstream socket."""

import argparse
from contextlib import ExitStack, contextmanager
import http.client
import json
import math
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time

from verify_cpu import MODEL


@contextmanager
def start(command, ready):
    with tempfile.TemporaryFile(mode="w+") as log, subprocess.Popen(
        command, stderr=subprocess.STDOUT, stdout=log,
    ) as process:
        try:
            deadline = time.monotonic() + 30
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    healthy = ready(log)
                except (ConnectionRefusedError, ConnectionResetError):
                    healthy = False
                if healthy:
                    yield log
                    return
                time.sleep(0.05)
            raise RuntimeError(f"Service did not start: {command[0]}")
        except BaseException:
            log.seek(0)
            print(log.read(), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def post(connection, data):
    connection.request("POST", "/embed", data, {"Content-Type": "text/plain"})


def request(port, data=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        if data is None:
            connection.request("GET", "/health")
        else:
            post(connection, data)
        with connection.getresponse() as response:
            return response.status, response.read()
    finally:
        connection.close()


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def frontend(executable, port, backend):
    return start([executable, str(port), str(backend)], lambda _: request(port) == (200, b"ok"))


def check_real(executable, model_cache):
    backend_port, port = free_port(), free_port()
    service = Path(__file__).with_name("serve_cpu.py")

    def cpu_ready(log):
        log.seek(0)
        return "CPU embedding service listening" in log.read()

    command = [sys.executable, service, model_cache, "--port", str(backend_port)]
    with start(command, cpu_ready), frontend(executable, port, backend_port):
        data = "Die Bibliothek verarbeitet eine Antwort mit UTF-8.".encode()
        status, payload = request(port, data)
        assert status == 200, (status, payload)
        assert (status, payload) == request(backend_port, data)
        result = json.loads(payload)
        assert result["model"] == MODEL and len(result["embedding"]) == 384
        assert math.isclose(math.hypot(*result["embedding"]), 1, rel_tol=0, abs_tol=1e-6)
        assert request(port, b" ")[0] == 400
        assert request(port, b"\xff")[0] == 400
        assert request(port, b"x" * 1025)[0] == 413


def wait_for_post(listener, stack):
    peer, _ = listener.accept()
    stack.enter_context(peer)
    peer.settimeout(5)
    with peer.makefile("rb") as incoming:
        assert incoming.readline() == b"POST /embed HTTP/1.1\r\n"
        while incoming.readline() not in (b"\r\n", b""):
            pass
        assert incoming.read(4) == b"test"
    return peer


def check_limits(executable):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(8)
        listener.settimeout(5)
        port = free_port()
        with frontend(executable, port, listener.getsockname()[1]) as log, ExitStack() as stack:
            clients = []
            peers = []
            for _ in range(4):
                client = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                stack.callback(client.close)
                post(client, b"test")
                clients.append(client)
                peers.append(wait_for_post(listener, stack))

            assert request(port, b"load")[0] == 503
            clients[0].close()
            assert peers[0].recv(1) == b""
            for client, peer in zip(clients[1:], peers[1:], strict=True):
                with client.getresponse() as response:
                    assert response.status == 504
                    response.read()
                assert peer.recv(1) == b""
            log.seek(0)
            assert "Embedding request cancelled" in log.read()

            post(clients[1], b"test")
            peer = wait_for_post(listener, stack)
            peer.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 16385\r\n\r\n")
            with clients[1].getresponse() as response:
                assert response.status == 502


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("model_cache", type=Path)
    args = parser.parse_args()
    check_real(args.executable.resolve(), args.model_cache)
    check_limits(args.executable.resolve())
    print("Real CPU embedding, request limits, overload, deadline and disconnect passed")
