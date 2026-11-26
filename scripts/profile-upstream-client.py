#!/usr/bin/env python3
"""Measure the optional HTTP/1.1 clients in serial loopback batches on Linux."""

import argparse
import csv
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
KTSERVE = ROOT / "components/ktserve/build/bin/native/upstreamProbeReleaseExecutable/ktserve-upstream-probe.kexe"
AXUM = ROOT / "competitors/rust-axum/target/release/axum-baseline"
CELLS = (
    ("ktserve", "cio", "fresh", KTSERVE),
    ("ktserve", "socket", "fresh", KTSERVE),
    ("ktserve", "socket", "keep-alive", KTSERVE),
    ("axum", "hyper", "fresh", AXUM),
    ("axum", "hyper", "keep-alive", AXUM),
)
LATENCIES = ("p50_us", "p95_us", "p99_us")
SYSCALLS = {
    "connect": {"connect"},
    "read_write": {"read", "readv", "write", "writev", "recvfrom", "recvmsg", "recvmmsg",
                   "sendto", "sendmsg", "sendmmsg"},
    "wait_wake": {"poll", "ppoll", "select", "pselect6", "epoll_wait", "epoll_pwait", "epoll_pwait2",
                  "epoll_ctl", "futex", "futex_waitv", "eventfd", "eventfd2", "sched_yield"},
    "virtual_memory": {"mmap", "munmap", "mremap", "mprotect", "madvise", "brk"},
}


def dump(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def pin(cpus):
    return ["taskset", "-c", ",".join(map(str, cpus))]


def origin_stats(port):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        conn.request("GET", "/stats")
        response = conn.getresponse()
        if response.status != 200:
            raise RuntimeError(f"Origin stats returned HTTP {response.status}")
        return json.load(response)
    finally:
        conn.close()


def wait_for_origin(origin, port):
    expires = time.monotonic() + 10
    while origin.poll() is None and time.monotonic() < expires:
        try:
            origin_stats(port)
            return
        except (OSError, http.client.HTTPException):
            time.sleep(0.05)
    raise RuntimeError("Origin failed to start. See origin.log.")


def run_client(command, env, path, requests):
    with path.with_suffix(".stdout").open("w") as stdout, path.with_suffix(".stderr").open("w") as stderr:
        return subprocess.run(command, env={**os.environ, **env}, stdout=stdout, stderr=stderr,
                              timeout=30 + requests * 0.3, check=False).returncode


def client_summary(path, requests):
    records = [json.loads(line) for line in path.read_text().splitlines() if line.startswith("{")]
    if len(records) != 1:
        raise ValueError(f"Expected one client result in {path}, got {len(records)}")
    result = records[0]
    if (result["requests"], result["expected"], result["unexpected"]) != (requests, requests, 0):
        raise ValueError(f"Unexpected request outcomes: {result}")
    latency = [float(result[key]) for key in LATENCIES]
    if any(not math.isfinite(value) or value < 0 for value in latency) or latency != sorted(latency):
        raise ValueError(f"Invalid latency percentiles in {path}")
    return {key: result[key] for key in (*LATENCIES, "requests", "expected", "unexpected")}


def connection_delta(before, after, scenario, mode, requests):
    keys = ("accepted", "requests", "reused")
    counts = {key: after["_connections"][key] - before["_connections"][key] for key in keys}
    accepted, total, reused = (counts[key] for key in keys)
    replied = after[scenario]["replied"] - before[scenario]["replied"]
    started = after[scenario]["started"] - before[scenario]["started"]
    if total != requests or started != requests or replied != requests or accepted + reused != total:
        raise ValueError(f"Unexpected origin request or connection counts: {counts}, {started=}, {replied=}")
    if mode == "fresh" and accepted != requests:
        raise ValueError(f"Fresh mode reused sockets: {counts}")
    if mode == "keep-alive" and not 0 < accepted < requests:
        raise ValueError(f"Keep-alive mode did not reuse sockets: {counts}")
    return {f"origin_{key}": value for key, value in counts.items()}


def measured(cell, env, path, cpus, port, scenario, requests, repetition):
    timing = path.with_suffix(".time")
    command = [*pin(cpus), "/usr/bin/time", "-q", "-o", str(timing), "-f", "%e %U %S %M", str(cell[3])]
    before = origin_stats(port)
    status = run_client(command, env, path, requests)
    after = origin_stats(port)
    dump(path.with_suffix(".origin.json"), {"before": before, "after": after})
    if status != 0:
        raise RuntimeError(f"{path.name} exited {status}. See its stdout and stderr.")
    elapsed, user, system, rss = timing.read_text().split()
    cpu = float(user) + float(system)
    return {
        "server": cell[0], "client": cell[1], "connection_mode": cell[2], "scenario": scenario,
        "repetition": repetition, "elapsed_s": float(elapsed), "cpu_seconds": round(cpu, 3),
        "user_cpu_seconds": float(user), "system_cpu_seconds": float(system),
        "cpu_seconds_per_million": round(cpu * 1_000_000 / requests, 3), "peak_rss_kib": int(rss),
        **client_summary(path.with_suffix(".stdout"), requests),
        **connection_delta(before, after, scenario, cell[2], requests),
    }


def syscall_groups(report):
    groups = (*SYSCALLS, "other")
    counts = dict.fromkeys(groups, 0)
    seconds = dict.fromkeys(groups, 0.0)
    for line in report.read_text().splitlines():
        fields = line.split()
        if len(fields) not in (5, 6) or not fields[3].isdigit() or fields[-1] == "total":
            continue
        name = fields[-1]
        group = next((group for group, names in SYSCALLS.items() if name in names), "other")
        counts[group] += int(fields[3])
        seconds[group] += float(fields[1])
    return {"calls": counts, "system_seconds": {group: round(total, 6) for group, total in seconds.items()}}


def strace_client(cell, env, path, cpus, requests):
    strace = shutil.which("strace")
    if not strace:
        return {"status": "unavailable", "reason": "strace not found"}
    report = path.with_suffix(".syscalls.txt")
    status = run_client([*pin(cpus), strace, "-f", "-c", "-o", str(report), str(cell[3])],
                        env, path, requests)
    if status == 0 and report.is_file():
        client_summary(path.with_suffix(".stdout"), requests)
        return {"status": "ok", "requests": requests, **syscall_groups(report)}
    return {"status": "unavailable", "reason": f"strace exit status {status}; see stderr"}


def perf_client(cell, env, path, cpus, requests):
    perf = shutil.which(os.environ.get("PERF_BIN", "perf"))
    if not perf:
        return {"status": "unavailable", "reason": "perf not found"}

    for elevated in (False, True):
        attempt = path.with_name(path.name + ("-sudo" if elevated else "-user"))
        data = attempt.with_suffix(".data")
        prefix = ["sudo", "-n", "--", "env", *(f"{key}={value}" for key, value in env.items())] if elevated else []
        if elevated and not shutil.which("sudo"):
            break
        record = [perf, "record", "-e", "cpu-clock", "-F", "999", "--call-graph", "dwarf,4096",
                  "-o", str(data), "--", str(cell[3])]
        status = run_client([*prefix, *pin(cpus), *record], env, attempt, requests)
        if elevated and data.is_file():
            subprocess.run(["sudo", "-n", "--", "chown", f"{os.getuid()}:{os.getgid()}", str(data)],
                           timeout=10, check=True)
        if status != 0 or not data.is_file():
            continue
        client_summary(attempt.with_suffix(".stdout"), requests)
        report = attempt.with_suffix(".report.txt")
        with report.open("w") as output:
            result = subprocess.run(
                [*prefix, perf, "report", "--stdio", "--sort", "dso,symbol", "-i", str(data)],
                stdout=output, stderr=subprocess.STDOUT, timeout=60, check=False,
            )
        if result.returncode == 0:
            return {"status": "ok", "requests": requests, "method": "sudo" if elevated else "user"}
    return {"status": "unavailable", "reason": "CPU-clock sampling failed; see perf attempt logs"}


def record_environment(args, client_cpus, origin_cpus):
    commands = (["git", "rev-parse", "HEAD"], ["uname", "-srm"], ["lscpu"], ["lscpu", "-e=CPU,CORE,SOCKET,NODE"],
                ["rustc", "--version"],
                ["java", "-version"], [sys.executable, "--version"], ["/usr/bin/time", "--version"])
    gradle = (ROOT / "components/ktserve/build.gradle.kts").read_text()
    with (args.output / "environment.txt").open("w") as output:
        print("http_protocol=HTTP/1.1\norigin=same-host-loopback", file=output)
        print("load=one client process issuing sequential requests directly to the origin", file=output)
        print("cost=GNU time for the entire process; diagnostic traces excluded from timings", file=output)
        print(f"client_cpus={client_cpus}\norigin_cpus={origin_cpus}\n{args}", file=output)
        for line in gradle.splitlines():
            if 'kotlin("multiplatform") version' in line or "ktor-client-cio" in line:
                print(line.strip(), file=output)
        output.flush()
        for command in commands:
            subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=True)
        for binary in (KTSERVE, AXUM):
            with binary.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            print(f"{digest}  {binary.relative_to(ROOT)}", file=output)


def compare(args, port, client_cpus, results):
    env = {
        "LC_ALL": "C", "BENCHMARK_SUITE": "application-upstream", "BENCHMARK_UPSTREAM_PORT": str(port),
        "UPSTREAM_CLIENT_ONLY": "1", "UPSTREAM_SCENARIO": args.scenario,
    }
    with results.open("w", newline="") as output:
        writer = None
        for repeat in range(args.repeats):
            for index in range(len(CELLS)):
                cell = CELLS[(index + repeat) % len(CELLS)]
                prefix = args.output / ("-".join(cell[:3]) + f"-{repeat + 1}")
                env.update(UPSTREAM_CLIENT=cell[1], UPSTREAM_CONNECTION_MODE=cell[2],
                           UPSTREAM_REQUESTS=str(args.requests))
                row = measured(cell, env, prefix, client_cpus, port, args.scenario, args.requests, repeat + 1)
                if writer is None:
                    writer = csv.DictWriter(output, fieldnames=row)
                    writer.writeheader()
                writer.writerow(row)
                output.flush()
                print(f"{prefix.name}: {row['cpu_seconds_per_million']} CPU s/M, "
                      f"{row['p99_us']} us p99, {row['origin_reused']} reused", flush=True)
    diagnostics = {}
    for cell in CELLS:
        env.update(UPSTREAM_CLIENT=cell[1], UPSTREAM_CONNECTION_MODE=cell[2],
                   UPSTREAM_REQUESTS=str(args.diagnostic_requests))
        name = "-".join(cell[:3])
        for tool, diagnostic in (("strace", strace_client), ("perf", perf_client)):
            if not getattr(args, tool):
                continue
            try:
                entry = diagnostic(cell, env, args.output / f"{name}-{tool}", client_cpus, args.diagnostic_requests)
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                entry = {"status": "unavailable", "reason": str(error)}
            diagnostics.setdefault(name, {})[tool] = entry
    if diagnostics:
        dump(args.output / "diagnostics.json", diagnostics)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--requests", type=int, default=1200)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--diagnostic-requests", type=int, default=400)
    parser.add_argument("--scenario", choices=("fast", "slow"), default="fast")
    parser.add_argument("--strace", action="store_true")
    parser.add_argument("--perf", action="store_true")
    args = parser.parse_args()
    if not 2 <= min(args.requests, args.diagnostic_requests) <= max(args.requests, args.diagnostic_requests) <= 1_000_000:
        parser.error("Use between 2 and 1,000,000 requests per batch.")
    if args.repeats < 1:
        parser.error("Use at least one repetition.")
    if not hasattr(os, "sched_getaffinity"):
        parser.error("The client-only harness requires Linux.")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        parser.error("At least four logical CPUs are required for disjoint placement.")
    client_cpus, origin_cpus = cpus[:2], cpus[3:4]

    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    results = args.output / "client-only.csv"
    if results.exists():
        parser.error("Use an output directory without an existing client-only.csv.")
    record_environment(args, client_cpus, origin_cpus)
    with socket.socket() as socket_probe:
        socket_probe.bind(("127.0.0.1", 0))
        port = socket_probe.getsockname()[1]
    origin_command = [*pin(origin_cpus), sys.executable, str(ROOT / "scripts/upstream-origin.py"),
                      str(port), "--keep-alive"]
    with (args.output / "origin.log").open("w") as origin_log, subprocess.Popen(
        origin_command, stdout=origin_log, stderr=subprocess.STDOUT,
    ) as origin:
        try:
            wait_for_origin(origin, port)
            compare(args, port, client_cpus, results)
        finally:
            origin.terminate()
            try:
                origin.wait(timeout=5)
            except subprocess.TimeoutExpired:
                origin.kill()
                origin.wait()


if __name__ == "__main__":
    main()
