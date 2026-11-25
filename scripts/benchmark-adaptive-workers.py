#!/usr/bin/env python3
"""Explore Linux worker scaling on one host with disjoint CPU affinity masks."""

import argparse
import csv
from hashlib import file_digest
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import re
import socket
from statistics import median
import subprocess
import time

from parse_vegeta import read_report
from parse_wrk import error_count, read_load_timing, read_stats


SCRIPTS = Path(__file__).resolve().parent
VARIANTS = {"fixed-1": 1, "fixed-2": 2, "adaptive": 1}
LOAD_TIMING = "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M"
ACTIVE_WORKERS = re.compile(r"^ktServe adaptive active_workers=([12])\b", re.MULTILINE)
ADAPTIVE_EVENTS = re.compile(r"^ktServe adaptive ((?:active_workers|retiring_worker|resumed_worker)=[12]) "
                             r"elapsed_ms=(\d+)$", re.MULTILINE)
CONNECTIONS = 64


def available_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def health(server, port):
    deadline = time.monotonic() + 5
    while server.poll() is None and time.monotonic() < deadline:
        connection = HTTPConnection("127.0.0.1", port, timeout=0.2)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            if response.status == 200 and response.read() == b"ok":
                return
        except OSError:
            time.sleep(0.05)
        finally:
            connection.close()
    raise RuntimeError(f"ktServe did not serve a valid health response on port {port}")


def proc_sample(pid):
    numbers = subprocess.check_output(["python3", SCRIPTS / "proc_metrics.py", str(pid)], text=True).split()
    return float(numbers[0]), int(numbers[1]), int(numbers[2])


def stop(server):
    if server.poll() is None:
        server.terminate()
    try:
        return server.wait(timeout=8)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait()
        raise RuntimeError("ktServe did not shut down on SIGTERM")


def phase(options, server, base, prefix, log, launched, seconds, offered_rate):
    timing = prefix.with_suffix(".client.txt")
    raw = prefix.with_suffix(".load.txt" if offered_rate is None else ".vegeta.bin")
    command = ["taskset", "-c", options.load_mask, "/usr/bin/time", "-o", timing, "-f", LOAD_TIMING]
    if offered_rate is None:
        command += ["wrk", "-s", SCRIPTS / "wrk-stats.lua", "--latency", "-t2", f"-c{CONNECTIONS}",
                    f"-d{seconds}s", "--timeout", "2s"]
        if options.profile == "churn":
            command += ["-H", "Connection: close"]
        command.append(f"{base}/json")
        targets = None
    else:
        command += ["vegeta", "attack", f"-rate={offered_rate}/s", f"-duration={seconds}s",
                    f"-workers={CONNECTIONS}", f"-max-workers={CONNECTIONS}", f"-connections={CONNECTIONS}",
                    f"-max-connections={CONNECTIONS}", "-timeout=2s", "-http2=false", "-redirects=-1", "-max-body=0"]
        if options.profile == "churn":
            command.append("-keepalive=false")
        targets = f"GET {base}/json\n".encode()

    before, _, rss_before = proc_sample(server.pid)
    started = time.monotonic()
    with raw.open("wb") as output, prefix.with_suffix(".stderr.txt").open("wb") as stderr:
        subprocess.run(command, input=targets, stdout=output, stderr=stderr, check=True, timeout=seconds + 10)
    ended = time.monotonic()
    after, peak, rss_after = proc_sample(server.pid)
    workers = ACTIVE_WORKERS.findall(log.read_text())
    if offered_rate is None:
        stats = read_stats(raw)
        requests = int(stats["requests"]) - int(stats["http_errors"])
        errors = error_count(stats)
        duration = int(stats["duration_us"]) / 1_000_000
        valid = requests > 0 and errors == 0
        latencies = {key: float(stats[key]) for key in ("p50_us", "p95_us", "p99_us")}
    else:
        report = prefix.with_suffix(".vegeta.json")
        with report.open("wb") as output:
            subprocess.run(["vegeta", "report", "-type=json", raw], stdout=output, check=True)
        summary = read_report(report, offered_rate * seconds)
        subprocess.run(["gzip", "-n", raw], check=True)
        requests = summary["ok"]
        errors = summary["attempted"] - requests
        duration = seconds
        valid = summary["valid"]
        latencies = {key: summary["latencies"][percentile] / 1000 for key, percentile in
                     (("p50_us", "50th"), ("p95_us", "95th"), ("p99_us", "99th"))}
    cpu = after - before
    return {
        "start_s": round(started - launched, 3), "end_s": round(ended - launched, 3),
        "offered_rps": offered_rate, "duration_s": duration, "accepted_responses": requests, "errors": errors,
        "rps": round(requests / duration, 3), **latencies, "valid": int(valid),
        "cpu_seconds": round(cpu, 6), "cpu_seconds_per_million": round(cpu / requests * 1e6, 3) if valid else None,
        "rss_before_kib": rss_before, "rss_after_kib": rss_after, "process_peak_rss_kib": peak,
        "active_workers_after": int(workers[-1]) if workers else None, **read_load_timing(timing),
    }


def trial(options, variant, repetition):
    work = options.output / f"{variant}-{repetition}"
    work.mkdir()
    port = available_port()
    base = f"http://127.0.0.1:{port}"
    env = {key: value for key, value in os.environ.items() if not key.startswith("KTSERVE_")}
    env.pop("BENCHMARK_SUITE", None)
    env.update(BIND_HOST="127.0.0.1", PORT=str(port), KTSERVE_WORKERS=str(VARIANTS[variant]),
               KTSERVE_BACKEND="epoll", KTSERVE_WORKER_LISTENER="shared", no_proxy="127.0.0.1", NO_PROXY="127.0.0.1")
    if variant == "adaptive":
        env["KTSERVE_ADAPTIVE_WORKERS"] = "1"
    log = work / "server.log"
    launched = time.monotonic()
    with log.open("wb") as output:
        server = subprocess.Popen(["taskset", "-c", options.server_mask, options.binary], env=env,
                                  stdout=output, stderr=subprocess.STDOUT)
    rows = []
    try:
        health(server, port)
        with (work / "smoke.txt").open("wb") as output:
            subprocess.run(["bash", SCRIPTS / "smoke.sh", base], env=env, stdout=output, check=True, timeout=15)
        phases = [
            ("warmup", options.warmup, options.quiet_rate),
            ("quiet-before", options.quiet, options.quiet_rate),
            ("burst", options.burst, None),
            ("recovery", options.recovery, options.quiet_rate),
            ("quiet-after", options.quiet, options.quiet_rate),
        ]
        for name, seconds, offered_rate in phases:
            row = phase(options, server, base, work / name, log, launched, seconds, offered_rate)
            rows.append({"variant": variant, "repetition": repetition, "phase": name, **row})
        health(server, port)
        log_text = log.read_text()
        expected = "ktServe backend=epoll tcp=ipv4"
        if variant == "fixed-2":
            expected += " workers=2"
        if variant == "adaptive":
            if ACTIVE_WORKERS.findall(log_text)[:1] != ["1"]:
                raise RuntimeError(f"Missing initial adaptive worker announcement in {log}")
        elif expected not in log_text.splitlines():
            raise RuntimeError(f"Missing fixed worker announcement in {log}")
    finally:
        exit_status = stop(server)
    if exit_status != 0:
        raise RuntimeError(f"ktServe exited with {exit_status}. See {log}")
    with (work / "phases.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)
    return rows


def report(output, profile, rows, repetitions):
    with (output / "phases.csv").open("w", newline="") as data:
        writer = csv.DictWriter(data, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)

    lines = [f"### Adaptive workers — {profile}", "",
             "Exploratory HTTP/1.1 `/json` load on a shared Linux host with disjoint CPU masks. "
             "Each phase starts a new load process. Persistent burst connections are reused within the burst.", "",
             "| Workers | Phase | Valid runs | Requests/s, median (min–max) | p99 ms | Server CPU s | End RSS MiB | Load CPUs used |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for variant in VARIANTS:
        for name in ("quiet-before", "burst", "recovery", "quiet-after"):
            group = [row for row in rows if row["variant"] == variant and row["phase"] == name and row["valid"]]
            if group:
                rates = [row["rps"] for row in group]
                values = [f"{median(rates):,.0f} ({min(rates):,.0f}–{max(rates):,.0f})",
                          f"{median(row['p99_us'] for row in group) / 1000:.3f}",
                          f"{median(row['cpu_seconds'] for row in group):.3f}",
                          f"{median(row['rss_after_kib'] for row in group) / 1024:.2f}",
                          f"{median(row['load_cpu_equivalents'] for row in group):.2f}"]
            else:
                values = ["—"] * 5
            lines.append("| " + " | ".join([variant, name, f"{len(group)}/{repetitions}", *values]) + " |")
    lines.extend(["", "Adaptive worker events by trial, timed from server startup.", ""])
    for index in range(1, repetitions + 1):
        log = (output / f"adaptive-{index}" / "server.log").read_text()
        transitions = [f"`{event}` at {int(elapsed) / 1000:.2f} s" for event, elapsed in ADAPTIVE_EVENTS.findall(log)]
        if "max_workers=1 reason=budget" in log:
            transitions.append("CPU budget limited the process to one worker")
        if "worker_failed=2" in log:
            transitions.append("worker two failed, see the server log")
        lines.append(f"- Trial {index} — {', '.join(transitions)}.")
    lines.extend(["",
                  "Latency, CPU and RSS use medians of valid runs. wrk counts completed responses and flags "
                  "HTTP status codes of 400 or higher. Vegeta requires HTTP 200 and delivery within 2% of the offered load. "
                  "Load CPU equivalents compare to a budget of two logical CPUs. A busy generator or a shared host "
                  "limits attribution to the server.", ""])
    markdown = "\n".join(lines)
    (output / "summary.md").write_text(markdown)
    print(markdown)


def main(options):
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        raise SystemExit("Use four available logical CPUs for disjoint server and load affinity")
    options.server_mask, options.load_mask = [",".join(map(str, group)) for group in (cpus[:2], cpus[2:4])]
    options.output.mkdir(parents=True, exist_ok=False)
    with options.binary.open("rb") as binary:
        digest = file_digest(binary, "sha256").hexdigest()
    environment = {**vars(options), "scope": "exploratory same-host Linux loopback",
                   "binary_sha256": digest, "commit": subprocess.check_output(
                       ["git", "-C", SCRIPTS.parent, "rev-parse", "HEAD"], text=True).strip(),
                   "kernel": os.uname().release, "architecture": os.uname().machine,
                   "os": Path("/etc/os-release").read_text(),
                   "cpu": subprocess.check_output(["lscpu"], text=True),
                   "vegeta": subprocess.check_output(["vegeta", "-version"], text=True).strip(),
                   "wrk": subprocess.run(["wrk", "--version"], stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, text=True).stdout.splitlines()[0],
                   "endpoint": "/json", "http": "1.1", "connections": CONNECTIONS}
    (options.output / "environment.json").write_text(json.dumps(environment, default=str, indent=2) + "\n")
    with (options.output / "cpu-topology.json").open("wb") as topology:
        subprocess.run(["python3", SCRIPTS / "worker-topology.py", options.server_mask, options.load_mask],
                       stdout=topology, check=True)
    variants = list(VARIANTS)
    rows = []
    for repetition in range(1, options.repetitions + 1):
        rotation = (repetition - 1) % len(variants)
        for variant in variants[rotation:] + variants[:rotation]:
            print(f"{options.profile} {variant} trial {repetition}", flush=True)
            rows.extend(trial(options, variant, repetition))
    report(options.output, options.profile, rows, options.repetitions)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--profile", choices=("persistent", "churn"), required=True)
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--quiet-rate", type=int, default=800)
    parser.add_argument("--warmup", type=int, default=4)
    parser.add_argument("--quiet", type=int, default=5)
    parser.add_argument("--burst", type=int, default=12)
    parser.add_argument("--recovery", type=int, default=13)
    options = parser.parse_args()
    if min(options.repetitions, options.quiet_rate, options.warmup, options.quiet, options.burst, options.recovery) < 1:
        parser.error("Use positive trial settings")
    options.binary = options.binary.resolve()
    if not options.binary.is_file() or not os.access(options.binary, os.X_OK):
        parser.error(f"Expected executable at {options.binary}")
    main(options)
