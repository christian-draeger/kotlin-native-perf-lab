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
from ktserve_worker_admission_smoke import assert_health, socket_count

HOST = "127.0.0.1"
CATALOG = b"GET /catalog/42 HTTP/1.1\r\nHost: localhost\r\n\r\n"
DOWNLOAD = b"GET /download HTTP/1.1\r\nHost: localhost\r\n\r\n"
DOWNLOAD_BODY = b"s" * (16 * 1024) + b"t" * (16 * 1024 + 3)
BATCH = 16
TRANSITION = re.compile(
    r"^ktServe adaptive ((?:active_workers|retiring_worker|parked_worker|resumed_worker|worker_failed)=\d+)\b",
    re.MULTILINE,
)


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
        if target.startswith("socket:[") or target in {"anon_inode:[eventpoll]", "anon_inode:[eventfd]"}:
            descriptors.add((descriptor.name, target))
    return descriptors


def task_ids(pid):
    return {task.name for task in Path(f"/proc/{pid}/task").iterdir()}


def epoll_descriptors(descriptors):
    return {fd for fd, target in descriptors if target == "anon_inode:[eventpoll]"}


def listening_epolls(pid):
    listeners = set()
    for fd in epoll_descriptors(transport_descriptors(pid)):
        try:
            registrations = Path(f"/proc/{pid}/fdinfo/{fd}").read_text()
        except FileNotFoundError:
            continue
        if re.search(r"^tfd:.*\bdata:\s*0+\s", registrations, re.MULTILINE):
            listeners.add(fd)
    return listeners


@contextmanager
def fixture(binary, cpu=None, handoff=False, policy="1"):
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
        "KTSERVE_ADAPTIVE_WORKERS": policy,
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


def wait_for_health_response(server, port):
    def ready():
        assert server.poll() is None, "Fixture exited before receiving requests"
        try:
            with ExitStack() as clients:
                assert_health(*connect(clients, port))
        except ConnectionRefusedError:
            return False
        return True

    wait_until(ready, "Fixture did not begin serving healthy responses")


def check_single_cpu_fallback(binary, policy):
    cpu = min(os.sched_getaffinity(0))
    with fixture(binary, cpu=cpu, policy=policy) as (server, port, logs):
        wait_for_health_response(server, port)
        assert os.sched_getaffinity(server.pid) == {cpu}
        assert len(epoll_descriptors(transport_descriptors(server.pid))) == 1, "Single-CPU fallback started extra event loops"
        server.terminate()
        assert server.wait(timeout=5) == 0, "Single-CPU fixture failed to shut down"
        output = logs.read_text()
        assert "ktServe adaptive max_workers=1 reason=budget" in output, output
        assert TRANSITION.findall(output) == ["active_workers=1"], output
    print(f"Adaptive policy {policy} used the fixed single-worker fallback with one available CPU")


def check_lifecycle(binary, standby=False):
    policy = "standby" if standby else "1"
    with fixture(binary, policy=policy, handoff=standby) as (server, port, logs):

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
        if standby:
            wait_until(lambda: "standby_ready=2" in logs.read_text(), "The prestarted worker did not become ready")
        with ExitStack() as warmup:
            peer, stream = connect(warmup, port)
            assert_health(peer, stream)
            # Warm the application dispatcher before identifying I/O threads across cycles.
            assert_download(peer)
            catalog = sample_catalog(peer, stream) * BATCH
        wait_until(lambda: socket_count(server.pid) == 1, "The warm-up connection did not close")
        baseline = transport_descriptors(server.pid)
        expected_epolls = 2 if standby else 1
        baseline_epolls = epoll_descriptors(baseline)
        assert len(baseline_epolls) == expected_epolls
        baseline_listeners = listening_epolls(server.pid)
        assert len(baseline_listeners) == 1, "Only the primary should accept connections before a burst"
        warm_tasks = task_ids(server.pid) if standby else set()

        for cycle in range(3):
            tasks_before_activation = task_ids(server.pid)
            with ExitStack() as observer:
                with ExitStack() as held:
                    stopped = Event()
                    with ThreadPoolExecutor(max_workers=4) as loads:
                        # Establish the pressure sockets while only the primary accepts.
                        pressure = [connect(held, port) for _ in range(4)]
                        futures = [loads.submit(apply_pressure, *client, catalog, stopped) for client in pressure]
                        try:
                            expected.append("active_workers=2")
                            wait_for_transitions(futures)
                            wait_until(lambda: len(listening_epolls(server.pid)) == 2, "Secondary did not accept connections")
                            if standby:
                                assert task_ids(server.pid) == tasks_before_activation, "Standby replaced a prestarted thread"
                                assert epoll_descriptors(transport_descriptors(server.pid)) == baseline_epolls, (
                                    "Standby replaced a prestarted poller"
                                )
                                active_tasks = set()
                            else:
                                active_tasks = task_ids(server.pid) - tasks_before_activation
                                assert len(active_tasks) == 1, f"Expected one new I/O worker task, found {active_tasks}"
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
                    assert listening_epolls(server.pid) == baseline_listeners, "Retiring worker continued accepting"
                    assert transitions() == expected, "Secondary exited with established connections still open"
                    # Open after the secondary pauses accepts, so this observer stays on the primary.
                    primary_peer = connect(observer, port)
                    assert_health(*primary_peer)
                    for client in pressure:
                        assert_health(*client)
                    for peer, _ in newcomers:
                        assert_download(peer)
                    assert_health(*primary_peer)
                    assert transitions() == expected, "A live exchange terminated worker retirement early"

                    if cycle == 2:
                        server.terminate()
                        assert server.wait(timeout=5) == 0, "Adaptive fixture failed to shut down during retirement"
                        for _, stream in [primary_peer, *pressure, *newcomers]:
                            assert stream.read(1) == b"", "Established connection survived server shutdown"
                        break

                if standby:
                    expected.append("parked_worker=2")
                expected.append("active_workers=1")

                def recovered():
                    # Exercise the same socket through cleanup while the supervisor reaps the worker.
                    assert_health(*primary_peer)
                    actual = transitions()
                    assert actual == expected[:len(actual)], (actual, expected)
                    if actual != expected:
                        return False
                    tasks = task_ids(server.pid)
                    return warm_tasks <= tasks and not active_tasks & tasks and listening_epolls(server.pid) == baseline_listeners

                wait_until(recovered, "Retirement did not restore the expected worker tasks and listener registrations")
                cleanup_count = len(re.findall(r"\bworker_cleanup_ms=\d+\b", logs.read_text()))
                assert cleanup_count == (0 if standby else cycle + 1), (
                    f"Unexpected dispatcher cleanup count {cleanup_count}"
                )
            wait_until(lambda: transport_descriptors(server.pid) == baseline, "Retirement leaked transport descriptors")
    print(f"Adaptive policy {policy} passed two retirements, reactivation, and shutdown with persistent connections")


def check_eager(binary):
    with fixture(binary, policy="eager") as (server, port, logs):
        wait_for_health_response(server, port)
        wait_until(lambda: len(listening_epolls(server.pid)) == 2, "Both eager workers did not register the listener")
        with ExitStack() as clients:
            peer, stream = connect(clients, port)
            assert_health(peer, stream)
            server.terminate()
            assert server.wait(timeout=5) == 0, "Eager fixture failed to shut down"
            assert stream.read(1) == b"", "A connection survived eager server shutdown"
        output = logs.read_text()
        assert "worker_policy=eager" in output and "ktServe backend=epoll tcp=ipv4 workers=2" in output, output
    print("Eager worker policy began serving with both event loops ready")


def has_two_cpus():
    quota_file = Path("/sys/fs/cgroup/cpu.max")
    quota, period = quota_file.read_text().split() if quota_file.is_file() else ("max", "0")
    return len(os.sched_getaffinity(0)) >= 2 and (quota == "max" or int(quota) >= 2 * int(period))


if __name__ == "__main__":
    binary = str(Path(sys.argv[1]).resolve())
    for policy in ("1", "standby", "eager"):
        check_single_cpu_fallback(binary, policy)
    if has_two_cpus():
        check_lifecycle(binary)
        check_lifecycle(binary, standby=True)
        check_eager(binary)
    else:
        print("Skipping adaptive worker lifecycle smoke: two effective CPUs are required")
