#!/usr/bin/env python3
"""Verify the Linux public worker API from a separately linked consumer."""

from contextlib import ExitStack, contextmanager
import os
from pathlib import Path
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory

from ktserve_adaptive_workers_smoke import epoll_descriptors, listening_epolls, transport_descriptors
from ktserve_http_smoke import connect, response
from ktserve_stress import assert_closed, fetch, request, wait_until
from ktserve_worker_admission_smoke import assert_health, open_healthy_client, socket_count


@contextmanager
def consumer(binary, cpus, parameters, overrides=None):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    command = [str(port)] if parameters is None else ["--worker-check", str(port), *parameters]
    affinity = ",".join(map(str, cpus))
    environment = {key: value for key, value in os.environ.items() if not key.startswith("KTSERVE_")}
    environment.update(overrides or {})
    with TemporaryDirectory() as temporary:
        log = Path(temporary) / "consumer.log"
        with log.open("w") as output:
            server = subprocess.Popen(["taskset", "-c", affinity, binary, *command], stdout=output, stderr=output,
                                      env=environment)
        try:
            yield server, port, log
        except BaseException:
            sys.stderr.write(log.read_text())
            raise
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


def assert_exchange(peer, stream, route, body):
    peer.sendall(request(route))
    status, _, received = response(stream)
    assert (status, received) == (200, body), (route, status, received)


def check_default_entrypoint(binary, cpus):
    with consumer(binary, cpus, None) as (server, port, _):
        wait_until(lambda: len(listening_epolls(server.pid)) == 2, "Default serve did not register two eager loops")
        assert fetch(port, "/") == (200, b"Hello from ktServe")


def check_mode(binary, cpus, mode, capacity, active, standby=False, overrides=None):
    with consumer(binary, cpus, [mode, str(capacity)], overrides) as (server, port, log):
        wait_until(lambda: "Public consumer ready" in log.read_text(), f"{mode} did not become ready")
        wait_until(lambda: len(listening_epolls(server.pid)) == active, f"{mode} did not register {active} listeners")
        assert len(epoll_descriptors(transport_descriptors(server.pid))) == (2 if standby else active)
        eager = mode == "eager" or (mode == "default" and overrides is None)
        if eager:
            assert f"ktServe worker_policy=eager workers={active}" in log.read_text()
        with ExitStack() as clients:
            first = open_healthy_client(clients, port)
            peers = [first]
            if active == 2:
                first[0].sendall(request("/hold"))
                wait_until(lambda: "Public consumer handler waiting" in log.read_text(), "First handler did not start")
                # The first loop cannot make progress until a second loop responds on another socket.
                second = open_healthy_client(clients, port)
                peers.append(second)
                assert_exchange(*second, "/release", b"ok")
                status, _, body = response(first[1])
                assert (status, body) == (200, b"released")
                assert_health(*first)
                if eager:
                    first[0].sendall(request("/blocking"))
                    wait_until(lambda: "Public consumer blocking handler waiting" in log.read_text(),
                               "Blocking route did not start")
                    second[0].sendall(request("/blocking"))
                    assert response(second[1])[0] == 503, "A second worker bypassed the blocking handler budget"
                    assert_exchange(*second, "/release", b"ok")
                    status, _, body = response(first[1])
                    assert (status, body) == (200, b"released")
            for _ in range(len(peers), capacity):
                peers.append(open_healthy_client(clients, port))
            assert socket_count(server.pid) == capacity + 1, "Workers exceeded or partitioned the shared limit"
            with connect(port) as overflow:
                assert_closed(overflow)
            removed, stream = peers.pop()
            stream.close()
            removed.close()
            wait_until(lambda: socket_count(server.pid) == capacity, "Disconnected client retained its admission slot")
            peers.append(open_healthy_client(clients, port))
            assert_exchange(*peers[0], "/stop", b"stopped")
            for peer, stream in peers:
                assert stream.read(1) == b"", "A keep-alive connection stayed open at shutdown"
            assert server.wait(timeout=5) == 0, log.read_text()
        assert log.read_text().count("Public consumer ready") == 1
    print(f"Public Linux serve passed mode={mode}, cpus={len(cpus)}, cap={capacity}, active={active}", flush=True)


if __name__ == "__main__":
    executable = str(Path(sys.argv[1]).resolve())
    cpus = sorted(os.sched_getaffinity(0))[:2]
    assert len(cpus) == 2, "The eager worker smoke needs two available CPUs"
    check_default_entrypoint(executable, cpus)
    check_mode(executable, cpus, "default", 4, 2)
    check_mode(executable, cpus[:1], "default", 4, 1)
    check_mode(executable, cpus, "default", 1, 1)
    check_mode(executable, cpus, "default", 4, 1, overrides={"KTSERVE_WORKERS": "1"})
    check_mode(executable, cpus, "1", 4, 1)
    check_mode(executable, cpus, "2", 4, 2)
    check_mode(executable, cpus, "eager", 4, 2, overrides={"KTSERVE_WORKERS": "1"})
    check_mode(executable, cpus, "standby", 4, 1, standby=True)
