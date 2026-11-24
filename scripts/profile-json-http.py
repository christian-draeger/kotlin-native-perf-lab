#!/usr/bin/env python3
"""Compare the same Kotlin/Native HTTP JSON fixture with three response paths."""

import argparse
from contextlib import contextmanager
import csv
import hashlib
import http.client
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

from parse_vegeta import read_report
from parse_wrk import read_load_timing


ROOT = Path(__file__).resolve().parents[1]
MODES = ("stock", "string-stream", "sink-stream")
RATES = {"/json": 20_000, "/catalog/123": 4_000}
CONNECTIONS = 16


def health(port):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        conn.request("GET", "/health")
        res = conn.getresponse()
        return res.status == 200 and res.read() == b"ok"
    finally:
        conn.close()


@contextmanager
def server(binary, cpu, mode, prefix, max_body=64 * 1024):
    with socket.socket() as selected:
        selected.bind(("127.0.0.1", 0))
        port = selected.getsockname()[1]
    env = dict(os.environ, KTSERVE_BACKEND="epoll", KTSERVE_EPOLL_TCP_NODELAY="on")
    with prefix.with_suffix(".server.txt").open("w") as log, subprocess.Popen(
        ["taskset", "-c", cpu, binary, "http", mode, str(port), str(max_body)],
        env=env, stdout=log, stderr=subprocess.STDOUT,
    ) as proc:
        try:
            deadline = time.monotonic() + 5
            while proc.poll() is None and time.monotonic() < deadline:
                try:
                    if health(port):
                        break
                except (OSError, http.client.HTTPException):
                    pass
                time.sleep(0.02)
            else:
                raise RuntimeError(f"Failed to open {prefix}")
            yield proc, port
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()


def smoke(binary, cpu, directory):
    for mode in MODES:
        prefix = directory / f"{mode}-check"
        with server(binary, cpu, mode, prefix) as (_, port):
            with prefix.with_suffix(".parity.txt").open("w") as output:
                subprocess.run(
                    [ROOT / "scripts/smoke.sh", f"http://127.0.0.1:{port}"],
                    env=dict(os.environ, BENCHMARK_SUITE="application"), check=True, stdout=output,
                )
            for _ in range(32):
                with socket.socket() as conn:
                    conn.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                    conn.settimeout(3)
                    conn.connect(("127.0.0.1", port))
                    conn.sendall(b"GET /catalog/123 HTTP/1.1\r\nHost: localhost\r\n\r\n" * 64)
                    assert conn.recv(1) == b"H"
            assert health(port)
        with server(binary, cpu, mode, directory / f"{mode}-overflow", max_body=4444) as (_, port):
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
            try:
                conn.request("GET", "/catalog/123")
                res = conn.getresponse()
                assert res.status == 500 and res.read() == b""
            finally:
                conn.close()
            assert health(port)


def load(port, path, rate, seconds, cpu, prefix):
    trace = prefix.with_suffix(".bin")
    timing = prefix.with_suffix(".time.txt")
    with trace.open("wb") as raw, prefix.with_suffix(".stderr").open("w") as errors:
        subprocess.run([
            "/usr/bin/time", "-o", timing, "-f", "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M",
            "taskset", "-c", cpu, "vegeta", "attack", f"-rate={rate}/s", f"-duration={seconds}s",
            f"-workers={CONNECTIONS}", f"-max-workers={CONNECTIONS}", f"-connections={CONNECTIONS}",
            f"-max-connections={CONNECTIONS}", "-timeout=2s", "-max-body=0", "-http2=false", "-redirects=-1",
        ], input=f"GET http://127.0.0.1:{port}{path}\n", text=True, stdout=raw, stderr=errors, check=True)
    report = prefix.with_suffix(".json")
    with trace.open("rb") as raw, report.open("w") as output:
        subprocess.run(["vegeta", "report", "-type=json"], stdin=raw, stdout=output, check=True)
    return read_report(report, rate * seconds), read_load_timing(timing)


def proc_metrics(pid):
    return subprocess.check_output([sys.executable, ROOT / "scripts/proc_metrics.py", str(pid)], text=True).split()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=lambda path: Path(path).resolve())
    parser.add_argument("output", type=Path)
    parser.add_argument("source_commit")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--seconds", type=int, default=5)
    parser.add_argument("--smoke-only", action="store_true")
    args = parser.parse_args()
    if min(args.repeat, args.warmup, args.seconds) < 1:
        parser.error("Use positive trial counts and durations")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 3:
        parser.error("The benchmark requires three available logical CPUs")
    server_cpu = str(cpus[0])
    load_cpu = ",".join(map(str, cpus[1:3]))
    args.output.mkdir(parents=True, exist_ok=False)
    smoke(args.binary, server_cpu, args.output)
    if args.smoke_only:
        return
    env = args.output / "environment.txt"
    with args.binary.open("rb") as binary, env.open("w") as output:
        output.write(f"source_sha={args.source_commit}\nrelease_sha256={hashlib.file_digest(binary, 'sha256').hexdigest()}\n")
        output.write(f"server_cpus={server_cpu}\nload_cpus={load_cpu}\nprotocol=HTTP/1.1\nconnections={CONNECTIONS}\n")
        output.write("backend=epoll\ntcp_nodelay=on\nbenchmark_source=exploratory same-host loopback\n")
        output.write(f"warmup_seconds={args.warmup}\nmeasure_seconds={args.seconds}\nrepetitions={args.repeat}\n")
        for command in (("uname", "-a"), ("lscpu",), ("vegeta", "-version")):
            output.write(subprocess.check_output(command, text=True, stderr=subprocess.STDOUT))
        output.write(Path("/etc/os-release").read_text())
    validity = []
    with (args.output / "results.csv").open("w", newline="") as results:
        for path, rate in RATES.items():
            for run in range(args.repeat):
                rotation = run % len(MODES)
                for mode in MODES[rotation:] + MODES[:rotation]:
                    stem = f"{path[1:].replace('/', '-')}-{run + 1}-{mode}"
                    with server(args.binary, server_cpu, mode, args.output / stem) as (proc, port):
                        warm, _ = load(port, path, rate, args.warmup, load_cpu, args.output / f"{stem}-warm")
                        before = float(proc_metrics(proc.pid)[0])
                        measured, client = load(port, path, rate, args.seconds, load_cpu, args.output / stem)
                        after, peak, rss = proc_metrics(proc.pid)
                    cpu = float(after) - before
                    valid = warm["valid"] and measured["valid"] and cpu >= 0
                    valid = valid and float(client["load_cpu_equivalents"]) < len(cpus[1:3])
                    validity.append(valid)
                    row = dict(
                        mode=mode, endpoint=path, run=run + 1, valid=int(valid),
                        rate=rate, offered=measured["planned"], attempted=measured["attempted"], successes=measured["ok"],
                        rps=measured["ok"] / args.seconds, p50_us=measured["latencies"]["50th"] / 1_000,
                        p95_us=measured["latencies"]["95th"] / 1_000, p99_us=measured["latencies"]["99th"] / 1_000,
                        server_cpu_seconds=round(cpu, 4),
                        cpu_seconds_per_million=round(cpu / measured["ok"] * 1e6, 4) if measured["ok"] else "",
                        peak_rss_kib=peak, end_rss_kib=rss, **client,
                    )
                    if len(validity) == 1:
                        writer = csv.DictWriter(results, fieldnames=row)
                        writer.writeheader()
                    writer.writerow(row)
                    results.flush()
                    print(f"{mode} {path} repeat {run + 1}: valid={valid}, CPU={cpu:.3f}s", flush=True)
    if not all(validity):
        raise RuntimeError("Some trials failed the request, load CPU or warm-up checks. See the raw files.")


if __name__ == "__main__":
    main()
