#!/usr/bin/env python3
"""Verify epoll admission recovers from a temporary descriptor limit."""

from contextlib import contextmanager
import os
from pathlib import Path
import resource
import socket
import subprocess
import sys
import tempfile
import time

from ktserve_http_smoke import response
from ktserve_stress import wait_until
from ktserve_worker_admission_smoke import HEALTH, assert_health, socket_count

HOST = "127.0.0.1"


def connect(port):
    return socket.create_connection((HOST, port), timeout=5)


def process_cpu_seconds(pid):
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")


def open_fds(pid):
    return len(list(Path(f"/proc/{pid}/fd").iterdir()))


@contextmanager
def temporarily_limit_descriptors(server):
    # Only the child process we started is affected. Its existing descriptors
    # remain usable. stdin, stdout and stderr occupy all slots below the soft limit.
    resource_type = resource.RLIMIT_NOFILE
    original = resource.prlimit(server.pid, resource_type)
    resource.prlimit(server.pid, resource_type, (3, original[1]))
    try:
        yield
    finally:
        if server.poll() is None:
            resource.prlimit(server.pid, resource_type, original)


def expect_paused(peer, timeout):
    peer.settimeout(timeout)
    try:
        received = peer.recv(1, socket.MSG_PEEK)
    except socket.timeout:
        return
    finally:
        peer.settimeout(5)
    raise AssertionError(f"Expected TCP admission to pause, received {received!r}")


def check_recovery(server, port):
    pid = server.pid
    before = open_fds(pid)
    with connect(port) as established, established.makefile("rb") as stream:
        assert_health(established, stream)
        with socket.socket() as pending:
            pending.settimeout(5)
            with temporarily_limit_descriptors(server):
                pending.connect((HOST, port))
                pending.sendall(HEALTH)
                started = time.monotonic()
                started_cpu = process_cpu_seconds(pid)
                expect_paused(pending, 0.6)
                elapsed = time.monotonic() - started
                consumed = process_cpu_seconds(pid) - started_cpu
                assert consumed < 0.5 * elapsed, f"Listener spun for {consumed:.3f}s in {elapsed:.3f}s"
                assert_health(established, stream)
            with pending.makefile("rb") as recovered:
                status, _, body = response(recovered)
                assert (status, body) == (200, b"ok"), (status, body)
        assert_health(established, stream)
    wait_until(lambda: open_fds(pid) <= before, "Recovered listener leaked descriptors")
    return elapsed, consumed


def check_shutdown(server, port):
    with connect(port) as established, established.makefile("rb") as stream:
        assert_health(established, stream)
        with socket.socket() as pending, temporarily_limit_descriptors(server):
            pending.settimeout(5)
            pending.connect((HOST, port))
            pending.sendall(HEALTH)
            expect_paused(pending, 0.2)
            server.terminate()
            assert server.wait(timeout=5) == 0, "Server failed to drain during admission backoff"
            assert stream.read(1) == b"", "Established socket stayed open during shutdown"


def run(binary, workers):
    with socket.socket() as listener:
        listener.bind((HOST, 0))
        port = listener.getsockname()[1]
    environment = {
        **os.environ,
        "BIND_HOST": HOST,
        "PORT": str(port),
        "KTSERVE_BACKEND": "epoll",
        "KTSERVE_WORKERS": str(workers),
        "KTSERVE_WORKER_LISTENER": "shared",
    }
    with tempfile.TemporaryFile() as log:
        server = subprocess.Popen([binary], env=environment, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        try:
            def is_ready():
                assert server.poll() is None, "Fixture exited before becoming ready"
                try:
                    with connect(port) as peer, peer.makefile("rb") as stream:
                        assert_health(peer, stream)
                    return True
                except ConnectionRefusedError:
                    return False

            wait_until(is_ready, "Release fixture never became ready")
            wait_until(lambda: socket_count(server.pid) == 1, "Fixture did not reach an idle socket baseline")
            elapsed, cpu = check_recovery(server, port)
            check_shutdown(server, port)
            print(f"{workers} epoll worker(s): recovered and drained, {cpu:.3f} CPU-s during {elapsed:.3f}s pressure")
        except BaseException:
            log.seek(0)
            sys.stderr.buffer.write(log.read())
            raise
        finally:
            if server.poll() is None:
                server.kill()
                server.wait(timeout=5)


if __name__ == "__main__":
    binary = str(Path(sys.argv[1]).resolve())
    for workers in (1, 2):
        run(binary, workers)
