#!/usr/bin/env python3
"""Measure post-GC heap and process RSS across idle connections on Linux."""

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import time


def request(connection, path):
    connection.request("GET", path)
    with connection.getresponse() as response:
        if response.status != 200:
            raise RuntimeError(f"{path} returned {response.status}")
        return response.read()


def sample(connection, pid):
    heap = int(request(connection, "/heap"))
    stats = {}
    for line in Path(f"/proc/{pid}/status").read_text().splitlines():
        key, _, value = line.partition(":")
        if key in ("VmRSS", "VmHWM"):
            stats[key] = int(value.split()[0])
    return {"heap_bytes": heap, "rss_kib": stats["VmRSS"], "peak_rss_kib": stats["VmHWM"]}


def ready(connection):
    deadline = time.monotonic() + 5
    while True:
        try:
            if request(connection, "/health") != b"ok":
                raise RuntimeError("Fixture did not return the expected health response")
            return
        except ConnectionRefusedError:
            connection.close()
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.02)


def samples(fixture, run):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    with subprocess.Popen([fixture, "--connection-retention", str(port)], stdout=subprocess.DEVNULL) as server:
        held = []
        control = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        try:
            ready(control)
            fd_dir = Path(f"/proc/{server.pid}/fd")
            initial_fds = len(list(fd_dir.iterdir()))
            for count in (0, 64, 128, 256, 512):
                while len(held) < count:
                    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    held.append(connection)
                    if request(connection, "/health") != b"ok" or connection.sock is None:
                        raise RuntimeError("Fixture did not hold the added connection open")
                yield {"run": run, "idle_connections": count, **sample(control, server.pid)}

            while held:
                held.pop().close()
            deadline = time.monotonic() + 3
            while len(list(fd_dir.iterdir())) > initial_fds:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Fixture did not release the disconnected sockets")
                time.sleep(0.02)
            yield {"run": run, "idle_connections": 0, "after_disconnect": True, **sample(control, server.pid)}
            if request(control, "/stop") != b"ok" or server.wait(timeout=4) != 0:
                raise RuntimeError("Fixture did not shut down cleanly")
        finally:
            control.close()
            for connection in held:
                connection.close()
            if server.poll() is None:
                server.kill()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("source_commit", help="Git commit used to build the release fixture")
    args = parser.parse_args()
    if os.uname().sysname != "Linux":
        parser.error("The process metrics require Linux /proc")
    fixture = args.fixture.resolve()
    with fixture.open("rb") as binary:
        digest = hashlib.file_digest(binary, "sha256").hexdigest()
    rows = [row for run in range(1, 6) for row in samples(fixture, run)]
    print(json.dumps({
        "source_sha": args.source_commit,
        "fixture_sha256": digest,
        "arch": os.uname().machine,
        "kernel": os.uname().release,
        "host_cpus": subprocess.check_output(["lscpu"], text=True).splitlines(),
        "os_release": Path("/etc/os-release").read_text().splitlines(),
        "measurements": rows,
    }, indent=2))


if __name__ == "__main__":
    main()
