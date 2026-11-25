#!/usr/bin/env python3
"""Exercise Linux worker activation and retirement on real HTTP connections."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
from http.client import HTTPResponse
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Event

from ktserve_stress import wait_until
from ktserve_worker_admission_smoke import assert_health

HOST = "127.0.0.1"
CATALOG = b"GET /catalog/42 HTTP/1.1\r\nHost: localhost\r\n\r\n"
DOWNLOAD = b"GET /download HTTP/1.1\r\nHost: localhost\r\n\r\n"
DOWNLOAD_BODY = b"s" * (16 * 1024) + b"t" * (16 * 1024 + 3)
BATCH = 16
TRANSITION = re.compile(r"^ktServe adaptive ((?:active_workers|retiring_worker|resumed_worker|worker_failed)=\d+)\b", re.MULTILINE)


def connect(clients, port):
    peer = clients.enter_context(socket.create_connection((HOST, port), timeout=5))
    return peer, clients.enter_context(peer.makefile("rb"))


def sample_catalog(peer, stream):
    peer.sendall(CATALOG)
    status = stream.readline()
    assert status == b"HTTP/1.1 200 OK\r\n", status
    headers = bytearray(status)
    size = None
    for line in iter(stream.readline, b"\r\n"):
        assert line, "Catalog response ended before the headers completed"
        headers.extend(line)
        if line.lower().startswith(b"content-length:"):
            size = int(line.split(b":", 1)[1])
    assert size is not None and size > 0, "Catalog response needs a nonempty body"
    body = stream.read(size)
    assert len(body) == size
    return bytes(headers) + b"\r\n" + body


def apply_pressure(peer, stream, expected, stopped):
    requests = CATALOG * BATCH
    while not stopped.is_set():
        peer.sendall(requests)
        assert stream.read(len(expected)) == expected, "Pipelined catalog responses changed or were truncated"


def assert_download(peer):
    peer.sendall(DOWNLOAD)
    with HTTPResponse(peer) as reply:
        reply.begin()
        assert (reply.status, reply.chunked) == (200, True)
        assert reply.read() == DOWNLOAD_BODY, "A retiring worker truncated a streamed response"


def transport_descriptors(pid):
    descriptors = set()
    for descriptor in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = os.readlink(descriptor)
        except FileNotFoundError:
            continue
        if target.startswith("socket:[") or target == "anon_inode:[eventpoll]":
            descriptors.add((descriptor.name, target))
    return descriptors


@contextmanager
def fixture(binary, cpu=None, handoff=False):
    with socket.socket() as probe:
        probe.bind((HOST, 0))
        port = probe.getsockname()[1]

    environment = {
        **os.environ,
        "BIND_HOST": HOST,
        "PORT": str(port),
        "BENCHMARK_SUITE": "application-mix",
        "KTSERVE_WORKERS": "1",
        "KTSERVE_WORKER_LISTENER": "shared",
        "KTSERVE_ADAPTIVE_WORKERS": "1",
    }
    environment.pop("KTSERVE_ADAPTIVE_HANDOFF", None)
    if handoff:
        environment["KTSERVE_ADAPTIVE_HANDOFF"] = "1"
    with TemporaryDirectory() as temporary:
        logs = Path(temporary) / "ktserve.log"
        with logs.open("wb") as output:
            affinity = os.sched_getaffinity(0)
            if cpu is not None:
                os.sched_setaffinity(0, {cpu})
            try:
                server = subprocess.Popen([binary], env=environment, stdout=output, stderr=output)
            finally:
                if cpu is not None:
                    os.sched_setaffinity(0, affinity)
        try:
            yield server, port, logs
        except BaseException:
            sys.stderr.write(logs.read_text())
            raise
        finally:
            if server.poll() is None:
                server.kill()
                server.wait(timeout=5)


def check_single_cpu_fallback(binary):
    cpu = min(os.sched_getaffinity(0))
    with fixture(binary, cpu=cpu) as (server, port, logs):
        def ready():
            assert server.poll() is None, "Single-CPU fixture exited before receiving requests"
            try:
                with ExitStack() as clients:
                    assert_health(*connect(clients, port))
            except ConnectionRefusedError:
                return False
            return True

        wait_until(ready, "Single-CPU fixture did not become ready")
        assert os.sched_getaffinity(server.pid) == {cpu}
        server.terminate()
        assert server.wait(timeout=5) == 0, "Single-CPU fixture failed to shut down"
        output = logs.read_text()
        assert "ktServe adaptive max_workers=1 reason=budget" in output, output
        assert TRANSITION.findall(output) == ["active_workers=1"], output
    print("Adaptive epoll workers used the fixed single-worker fallback with one available CPU")


def check_lifecycle(binary):
    with fixture(binary) as (server, port, logs):

        expected = ["active_workers=1"]

        def transitions():
            return TRANSITION.findall(logs.read_text())

        def wait_for_transitions(futures=()):
            def ready():
                assert server.poll() is None, "Fixture exited during the worker experiment"
                for future in futures:
                    if future.done():
                        future.result()
                actual = transitions()
                assert actual[:len(expected)] == expected[:len(actual)], (actual, expected)
                return len(actual) >= len(expected)

            wait_until(ready, f"Fixture did not report {expected[-1]}", seconds=30)

        wait_for_transitions()
        with ExitStack() as controls:
            established = connect(controls, port)
            assert_health(*established)
            catalog = sample_catalog(*established) * BATCH
            baseline = transport_descriptors(server.pid)

            for final_cycle in (False, True):
                with ExitStack() as held:
                    stopped = Event()
                    with ExitStack() as traffic, ThreadPoolExecutor(max_workers=4) as loads:
                        # These sockets are all assigned before there is a secondary worker.
                        pressure = [connect(traffic, port) for _ in range(4)]
                        futures = [loads.submit(apply_pressure, *client, catalog, stopped) for client in pressure]
                        try:
                            expected.append("active_workers=2")
                            wait_for_transitions(futures)
                            newcomers = []
                            for _ in range(32):
                                client = connect(held, port)
                                assert_health(*client)
                                newcomers.append(client)
                        finally:
                            stopped.set()
                        for future in futures:
                            future.result()

                    expected.append("retiring_worker=2")
                    wait_for_transitions()
                    assert transitions() == expected, "Secondary exited with established connections still open"
                    with ExitStack() as fresh:
                        assert_health(*connect(fresh, port))
                    for peer, _ in newcomers:
                        assert_download(peer)
                    assert_health(*established)
                    assert transitions() == expected, "A live exchange terminated worker retirement early"

                    if final_cycle:
                        server.terminate()
                        assert server.wait(timeout=5) == 0, "Adaptive fixture failed to shut down during retirement"
                        for _, stream in [established, *newcomers]:
                            assert stream.read(1) == b"", "Established connection survived server shutdown"
                        break

                expected.append("active_workers=1")
                wait_for_transitions()
                wait_until(lambda: transport_descriptors(server.pid) == baseline, "Retirement left transport descriptors open")
                assert_health(*established)
    print("Adaptive epoll workers passed activation, retirement, reactivation, and shutdown with persistent connections")


def has_two_cpus():
    quota_file = Path("/sys/fs/cgroup/cpu.max")
    quota, period = quota_file.read_text().split() if quota_file.is_file() else ("max", "0")
    return len(os.sched_getaffinity(0)) >= 2 and (quota == "max" or int(quota) >= 2 * int(period))


if __name__ == "__main__":
    binary = str(Path(sys.argv[1]).resolve())
    check_single_cpu_fallback(binary)
    if has_two_cpus():
        check_lifecycle(binary)
    else:
        print("Skipping adaptive worker lifecycle smoke: two effective CPUs are required")
