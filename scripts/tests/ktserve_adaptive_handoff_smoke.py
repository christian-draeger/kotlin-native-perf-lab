#!/usr/bin/env python3
"""Verify Linux adaptive workers keep established TCP exchanges intact during handoff."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from pathlib import Path
import re
import socket
import sys
from threading import Event
import time

from ktserve_adaptive_workers_smoke import (
    BATCH,
    apply_pressure,
    assert_download,
    connect,
    fixture,
    has_two_cpus,
    sample_catalog,
    transport_descriptors,
)
from ktserve_http_smoke import response
from ktserve_stress import wait_until
from ktserve_worker_admission_smoke import CONNECTIONS, HEALTH, assert_health, socket_count


def tcp_inodes(pid, port, peers):
    """Map the original client source ports to their server-side kernel sockets."""
    source_ports = {peer.getsockname()[1] for peer, _ in peers}
    inodes = {}
    for line in Path(f"/proc/{pid}/net/tcp").read_text().splitlines()[1:]:
        _, local, remote, state, *fields = line.split()
        if state != "01" or int(local.split(":")[1], 16) != port:
            continue
        source_port = int(remote.split(":")[1], 16)
        if source_port in source_ports:
            inodes[source_port] = int(fields[5])
    assert inodes.keys() == source_ports, (inodes, source_ports)
    return inodes


def epoll_registrations(pid):
    registrations = {}
    for descriptor, target in transport_descriptors(pid):
        if target != "anon_inode:[eventpoll]":
            continue
        try:
            info = Path(f"/proc/{pid}/fdinfo/{descriptor}").read_text()
        except FileNotFoundError:
            continue
        inodes = re.findall(r"^tfd:.*\bino:\s*([0-9a-f]+)", info, re.M)
        registrations[descriptor] = {int(inode, 16) for inode in inodes}
    return registrations


def fragmented_head(peer, stream):
    peer.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r")
    time.sleep(0.025)
    peer.sendall(b"\n")
    status, _, body = response(stream)
    assert (status, body) == (200, b"ok")


def fragmented_body(peer, stream):
    peer.sendall(b"POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 6\r\n\r\nxxx")
    time.sleep(0.025)
    peer.sendall(b"xxx")
    status, _, body = response(stream)
    assert (status, body) == (200, b"6")


def repeat_exchange(exchange, connection, stopped):
    while not stopped.is_set():
        exchange(*connection)


def paced_health(peer, stream):
    assert_health(peer, stream)
    time.sleep(0.025)


def assert_pipeline(peer, stream):
    peer.sendall(HEALTH + b"GET /users/123 HTTP/1.1\r\nHost: localhost\r\n\r\n")
    status, _, body = response(stream)
    assert (status, body) == (200, b"ok")
    status, _, body = response(stream)
    assert (status, body) == (200, b'{"id":123,"name":"benchmark-user","active":true}')


def check_migrated_slot(server_pid, port, peers, moved_peer):
    with ExitStack() as capacity:
        for _ in range(CONNECTIONS - len(peers)):
            assert_health(*connect(capacity, port))
        assert socket_count(server_pid) == CONNECTIONS + 1, "Handoff changed the shared connection limit"
        with ExitStack() as rejected:
            overflow, _ = connect(rejected, port)
            try:
                overflow.sendall(HEALTH)
                assert overflow.recv(1) == b"", "Handoff released a connection slot too early"
            except (BrokenPipeError, ConnectionResetError):
                pass

        moved_peer[0].shutdown(socket.SHUT_WR)
        assert moved_peer[1].read(1) == b"", "The secondary did not process a migrated peer's half-close"
        wait_until(lambda: socket_count(server_pid) == CONNECTIONS, "Closing a migrated peer left a socket open")
        assert_health(*connect(capacity, port))


def check_handoff(binary):
    with fixture(binary, handoff=True) as (server, port, logs), ExitStack() as clients:
        wait_until(lambda: "active_workers=1" in logs.read_text(), "Adaptive fixture did not start")
        hot = [connect(clients, port) for _ in range(6)]
        partial_head, partial_body, stream_peer = [connect(clients, port) for _ in range(3)]
        all_peers = [*hot, partial_head, partial_body, stream_peer]
        for connection in all_peers:
            assert_health(*connection)
        catalog = sample_catalog(*hot[0]) * BATCH
        original_inodes = tcp_inodes(server.pid, port, all_peers)
        original_sockets = set(original_inodes.values())
        original_hot = {original_inodes[peer.getsockname()[1]] for peer, _ in hot}

        def initial_owner():
            return next((fd for fd, sockets in epoll_registrations(server.pid).items() if original_sockets <= sockets), None)

        wait_until(initial_owner, "The primary epoll loop did not own the original sockets")
        primary = initial_owner()
        stopped = Event()

        # Keep the original sockets as the only load until the split is observable.
        with ThreadPoolExecutor(max_workers=len(all_peers)) as pool:
            # Two actively used connections leave request boundaries available during pipeline pressure.
            futures = [pool.submit(apply_pressure, *connection, catalog, stopped) for connection in hot[:4]]
            futures += [pool.submit(repeat_exchange, paced_health, connection, stopped) for connection in hot[4:]]
            futures += [pool.submit(repeat_exchange, exchange, connection, stopped) for exchange, connection in (
                (fragmented_head, partial_head), (fragmented_body, partial_body),
                (lambda peer, _: assert_download(peer), stream_peer),
            )]

            def handoff_reported():
                assert server.poll() is None, "Fixture exited during handoff"
                for future in futures:
                    if future.done():
                        future.result()
                output = logs.read_text()
                assert "worker_failed=2" not in output, output
                return "active_workers=2" in output and re.search(r"\bhandoff_received=[1-9]\d*\b", output)

            try:
                wait_until(handoff_reported, "No keep-alive handoff was received", seconds=30)
            finally:
                stopped.set()
            for future in futures:
                future.result()

        moved = set()

        def split_owners():
            registrations = epoll_registrations(server.pid)
            first = original_sockets & registrations.get(primary, set())
            others = original_sockets & set().union(*(sockets for fd, sockets in registrations.items() if fd != primary))
            if not first & original_hot or not others & original_hot:
                return False
            if first | others != original_sockets or not first.isdisjoint(others):
                return False
            moved.update(others)
            return True

        wait_until(split_owners, "Existing hot sockets were not split between distinct epoll owners")
        assert tcp_inodes(server.pid, port, all_peers) == original_inodes, "A TCP connection changed during handoff"
        socket_names = {f"socket:[{inode}]" for inode in original_sockets}
        descriptors = [fd for fd, target in transport_descriptors(server.pid) if target in socket_names]
        assert len(descriptors) == len(all_peers), "Handoff left duplicate or missing socket descriptors"

        for connection in hot:
            assert_pipeline(*connection)
        fragmented_head(*partial_head)
        fragmented_body(*partial_body)
        assert_download(stream_peer[0])

        moved_peer = next((peer, stream) for peer, stream in hot if original_inodes[peer.getsockname()[1]] in moved)
        check_migrated_slot(server.pid, port, all_peers, moved_peer)

        server.terminate()
        assert server.wait(timeout=5) == 0, "Adaptive fixture failed to shut down after handoff"
        for _, stream in all_peers:
            assert stream.read(1) == b"", "A peer survived server shutdown"
    print("Adaptive epoll handed off existing TCP sockets while pipelined and streaming exchanges stayed intact")


if __name__ == "__main__":
    if has_two_cpus():
        check_handoff(str(Path(sys.argv[1]).resolve()))
    else:
        print("Skipping adaptive worker handoff smoke: two effective CPUs are required")
