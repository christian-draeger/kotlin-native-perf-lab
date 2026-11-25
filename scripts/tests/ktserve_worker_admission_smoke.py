#!/usr/bin/env python3
"""Verify the shared epoll listener uses the entire process connection budget."""

from contextlib import ExitStack
import os
from pathlib import Path
import signal
import sys

from ktserve_http_smoke import connect, response
from ktserve_stress import wait_until

CONNECTIONS = 1024
HEALTH = b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n"


def socket_count(pid):
    sockets = set()
    for descriptor in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = os.readlink(descriptor)
        except FileNotFoundError:
            continue
        if target.startswith("socket:["):
            sockets.add(target)
    return len(sockets)


def assert_health(peer, stream):
    peer.sendall(HEALTH)
    status, _, body = response(stream)
    assert (status, body) == (200, b"ok"), (status, body)


def open_healthy_client(clients, port):
    peer = clients.enter_context(connect(port))
    stream = clients.enter_context(peer.makefile("rb"))
    assert_health(peer, stream)
    return peer, stream


def main(port, pid):
    # Both worker listener descriptors refer to the same socket inode.
    wait_until(lambda: socket_count(pid) == 1, "The shared listener did not become idle")

    with ExitStack() as clients:
        held = []
        for index in range(CONNECTIONS):
            try:
                held.append(open_healthy_client(clients, port))
            except (AssertionError, OSError) as error:
                raise AssertionError(
                    f"Shared-listener connection {index + 1} failed within the {CONNECTIONS}-connection limit"
                ) from error
        assert socket_count(pid) == CONNECTIONS + 1, "The process did not hold exactly its configured limit"

        with connect(port) as overflow:
            try:
                overflow.sendall(HEALTH)
                assert overflow.recv(1) == b"", "A request exceeded the process connection limit"
            except (BrokenPipeError, ConnectionResetError):
                pass
        assert socket_count(pid) == CONNECTIONS + 1, "A capacity rejection changed the open connection count"

        peer, stream = held.pop(CONNECTIONS // 2)
        assert_health(peer, stream)
        stream.close()
        peer.close()
        wait_until(lambda: socket_count(pid) == CONNECTIONS, "Closing an active connection did not return its slot")
        held.append(open_healthy_client(clients, port))
        assert socket_count(pid) == CONNECTIONS + 1, "A new connection did not consume the returned slot"
        assert_health(*held[0])

        os.kill(pid, signal.SIGTERM)
        for _, stream in held:
            assert stream.read(1) == b"", "An idle connection stayed open during shutdown"
    print(f"Shared epoll admission passed ({CONNECTIONS} persistent connections, overflow, recovery, shutdown)")


if __name__ == "__main__":
    main(int(sys.argv[1]), int(sys.argv[2]))
