#!/usr/bin/env python3
"""Run a matched, exploratory h2c /json pilot for ktServe and Axum on Linux."""

import argparse
import csv
import gzip
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
EXPECTED = '{"id":42,"name":"benchmark-user","active":true}\n2 200 application/json'
CONNECTIONS = 16
STREAM_COUNTS = (1, 8)
LOAD_THREADS = 2


def command_output(*command):
    return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True).strip()


def wait_for_json(server, url, path):
    deadline = time.monotonic() + 10
    while server.poll() is None and time.monotonic() < deadline:
        response = subprocess.run([
            "curl", "--noproxy", "*", "--http2-prior-knowledge", "-fsS", "--max-time", "1",
            "--write-out", "\n%{http_version} %{http_code} %{content_type}", url,
        ], capture_output=True, text=True)
        if response.returncode == 0:
            path.write_text(response.stdout + "\n")
            if response.stdout == EXPECTED:
                return
            raise ValueError(f"Expected HTTP/2 200 and the exact JSON payload. See {path}")
        time.sleep(0.1)
    raise RuntimeError(f"Server never returned /json. See the server log next to {path}")


def load(log, prefix, url, streams, duration, requests=None):
    command = [
        "taskset", "-c", prefix, "/usr/bin/time", "-f", "load_time %U %S %e %M",
        "h2load", "-p", "h2c", "-c", str(CONNECTIONS), "-m", str(streams),
        "-t", str(LOAD_THREADS), "-D", str(duration), "-N", "2",
    ]
    if requests is not None:
        command.append(f"--log-file={requests}")
    with log.open("w") as output:
        subprocess.run([*command, url], stdout=output, stderr=subprocess.STDOUT,
                       timeout=duration + 15, check=True, env={**os.environ, "LC_ALL": "C"})
    return log.read_text()


def load_result(log, requests):
    rate = re.search(r"finished in .*?, ([\d.]+) req/s", log)
    counts = re.search(r"requests: \d+ total, \d+ started, (\d+) done, (\d+) succeeded, "
                       r"(\d+) failed, (\d+) errored, (\d+) timeout", log)
    usage = re.search(r"^load_time ([\d.]+) ([\d.]+) ([\d.]+) (\d+)$", log, re.M)
    if not (rate and counts and usage and re.search(r"^Application protocol: h2c?$", log, re.M)):
        raise ValueError("Missing HTTP/2 negotiation, request counts, throughput or load process timing")
    done, successful, failed, errored, timed_out = map(int, counts.groups())
    user, system, wall, peak = map(float, usage.groups())
    latencies = []
    unexpected_statuses = 0
    with requests.open() as rows:
        for row in rows:
            _, status, elapsed, *_ = row.split()
            if status == "200":
                latencies.append(int(elapsed))
            else:
                unexpected_statuses += 1
    latencies.sort()
    valid = int(len(latencies) == successful == done > 0 and not (
        failed or errored or timed_out or unexpected_statuses
    ))
    percentiles = {
        f"p{p}_ms": latencies[math.ceil(len(latencies) * p / 100) - 1] / 1000 if latencies else ""
        for p in (50, 95, 99)
    }
    with requests.open("rb") as raw, gzip.open(str(requests) + ".gz", "wb") as compressed:
        shutil.copyfileobj(raw, compressed)
    requests.unlink()
    return {
        "requests_per_second": float(rate[1]), **percentiles, "completed": done, "successes": successful,
        "failed": failed, "errored": errored, "timeouts": timed_out, "unexpected_statuses": unexpected_statuses,
        "load_cpu_seconds": round(user + system, 3), "load_peak_rss_kib": int(peak),
        "load_cpu_equivalents": round((user + system) / wall, 3) if wall else "", "valid": valid,
    }


def trial(args, variant, streams, repetition, server_cpus, load_cpus):
    label = args.output / f"{variant}-{streams}streams-{repetition}"
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    executable = getattr(args, variant)
    arguments = [str(port)] if variant == "ktserve" else []
    env = {**os.environ, "BIND_HOST": "127.0.0.1", "PORT": str(port), "TOKIO_WORKER_THREADS": "2"}
    url = f"http://127.0.0.1:{port}/json"
    metrics = [sys.executable, str(ROOT / "scripts/proc_metrics.py")]
    with label.with_suffix(".server.log").open("w") as output:
        server = subprocess.Popen(["taskset", "-c", server_cpus, str(executable), *arguments],
                                  stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            wait_for_json(server, url, label.with_suffix(".preflight.log"))
            warmup = load(label.with_suffix(".warmup.log"), load_cpus, url, streams, args.warmup)
            if not re.search(r"^Application protocol: h2c?$", warmup, re.M):
                raise ValueError(f"Warm-up did not negotiate h2c. See {label}.warmup.log")
            before, *_ = command_output(*metrics, str(server.pid)).split()
            requests = label.with_suffix(".requests.tsv")
            measured = load(label.with_suffix(".h2load.log"), load_cpus, url, streams, args.duration, requests)
            after, peak, resident = command_output(*metrics, str(server.pid)).split()
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
    return {
        "variant": variant, "endpoint": "/json", "connections": CONNECTIONS, "streams": streams,
        "run": repetition, "warmup_s": args.warmup, "target_duration_s": args.duration, "port": port,
        "server_cpu_seconds": round(float(after) - float(before), 6), "peak_rss_kib": int(peak),
        "rss_kib": int(resident), **load_result(measured, requests),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ktserve", "axum", "output"):
        parser.add_argument(f"--{name}", type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--duration", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if min(args.warmup, args.duration, args.repeats) < 1:
        parser.error("Warm-up, duration and repeats must be positive integers")
    if not all(os.access(binary, os.X_OK) for binary in (args.ktserve, args.axum)):
        parser.error("Build both release executables first")
    if sys.platform != "linux":
        parser.error("This pilot uses Linux process and CPU affinity metrics")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 2 + LOAD_THREADS:
        parser.error("The pilot needs two logical CPUs for the server and two for the load generator")
    server_cpus = f"{cpus[0]},{cpus[1]}"
    load_cpus = ",".join(map(str, cpus[2:2 + LOAD_THREADS]))
    args.output.mkdir(parents=True, exist_ok=False)
    sha = command_output("git", "-C", str(ROOT), "rev-parse", "HEAD")
    status = command_output("git", "-C", str(ROOT), "status", "--porcelain")
    metadata = (
        f"git_sha={sha}\ngit_worktree={'dirty' if status else 'clean'}\nmode=exploratory; same VM\n"
        f"protocol=h2c prior knowledge\nTLS=off\nserver_cpus={server_cpus}\nload_cpus={load_cpus}\n"
        f"load_threads={LOAD_THREADS}\nTOKIO_WORKER_THREADS=2\n"
    )
    with (args.output / "environment.txt").open("w") as output:
        output.write(metadata)
        for command in (
            ("uname", "-srm"), ("lscpu",), ("lscpu", "-e"), ("h2load", "--version"),
            ("curl", "--version"), ("sha256sum", str(args.ktserve), str(args.axum)),
        ):
            output.write(command_output(*command) + "\n")
    invalid = False
    writer = None
    with (args.output / "results.csv").open("w", newline="") as output:
        for repetition in range(1, args.repeats + 1):
            variants = ("ktserve", "axum") if repetition % 2 else ("axum", "ktserve")
            for streams in STREAM_COUNTS:
                for variant in variants:
                    row = trial(args, variant, streams, repetition, server_cpus, load_cpus)
                    if writer is None:
                        writer = csv.DictWriter(output, fieldnames=row.keys())
                        writer.writeheader()
                    writer.writerow(row)
                    output.flush()
                    invalid |= row["valid"] == 0
                    print(f"{variant} {streams} streams run {repetition}: "
                          f"{row['requests_per_second']:.2f} req/s, p99 {row['p99_ms']} ms, valid={row['valid']}",
                          flush=True)
    if invalid:
        raise SystemExit(f"HTTP/2 pilot contains invalid trials. See {args.output}")


if __name__ == "__main__":
    main()
