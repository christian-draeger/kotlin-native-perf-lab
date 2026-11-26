#!/usr/bin/env python3
"""Check and measure identical handlers calling a real loopback HTTP origin."""

import argparse
import contextlib
from collections import Counter
import csv
import hashlib
import http.client
import json
import os
import platform
from pathlib import Path
import socket
import subprocess
import sys
import time

from parse_wrk import read_load_timing

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
PROBE = ROOT / "components/ktserve/build/bin/native/upstreamProbeReleaseExecutable/ktserve-upstream-probe.kexe"
VARIANTS = {
    name: (str(SCRIPTS / "run-server.sh"), name)
    for name in ("rust-axum", "ktor-jvm", "ktor-native")
}
VARIANTS = {"ktserve": (str(PROBE),), **VARIANTS}
CASES = {
    "fast": (70, 200, "origin-ready"),
    "slow": (20, 200, "origin-ready"),
    "deadline": (5, 504, "origin-deadline"),
    "disconnect": (5, 502, "origin-unavailable"),
}


def request(port, path):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        return response.status, response.version, response.headers, response.read().decode()
    finally:
        connection.close()


def ready(process, port, path):
    expiry = time.monotonic() + 30
    while process.poll() is None and time.monotonic() < expiry:
        try:
            if request(port, path)[0] == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(0.1)
    raise RuntimeError(f"{process.args} did not start. See its log.")


@contextlib.contextmanager
def launch(command, cpus, log_path, **env):
    with log_path.open("w") as log:
        process = subprocess.Popen(
            [*affinity(cpus), *command], env={**os.environ, **env}, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            yield process
        finally:
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def affinity(cpus):
    return ["taskset", "-c", ",".join(map(str, cpus))] if cpus else []


def open_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def origin_stats(port):
    return json.loads(request(port, "/stats")[3])


def counter_delta(before, after, group, key):
    return after[group][key] - before[group][key]


def preflight(front_port, origin_port, log, connection_mode=None):
    before = origin_stats(origin_port)
    checks = {**CASES, "unknown": (0, 404, "unknown-scenario")}
    scenarios = list(checks)
    if connection_mode:
        # Exercise healthy reuse before and after each fault on the same frontend.
        scenarios = ["fast", "slow", "deadline", "fast", "disconnect", "fast", "slow", "unknown"]
    after_disconnect = None
    for name in scenarios:
        _, expected, body = checks[name]
        status, version, headers, payload = request(front_port, f"/upstream/{name}")
        if (status, version, headers.get_content_type(), payload) != (expected, 11, "text/plain", body):
            raise RuntimeError(f"Unexpected {name} response: {status}, HTTP {version}, {payload!r}")
        if connection_mode and name == "disconnect":
            after_disconnect = origin_stats(origin_port)
    expires = time.monotonic() + 1
    while time.monotonic() < expires:
        after = origin_stats(origin_port)
        if sum(counter_delta(before, after, "deadline", key) for key in ("peer_closed", "replied")):
            break
        time.sleep(0.02)
    after = origin_stats(origin_port)
    snapshots = {"before": before, "after": after}
    if after_disconnect is not None:
        snapshots["after_disconnect"] = after_disconnect
    log.write_text(json.dumps(snapshots, indent=2) + "\n")
    expected = Counter(scenarios)
    for name in CASES:
        if counter_delta(before, after, name, "started") != expected[name]:
            raise RuntimeError(f"{name} did not make exactly one upstream request per call; see {log}")
    if connection_mode:
        if (counter_delta(before, after, "deadline", "peer_closed") != 1
                or counter_delta(before, after, "deadline", "replied") != 0):
            raise RuntimeError(f"The deadline did not cancel the upstream connection; see {log}")
        validate_connections(before, after, connection_mode, len(scenarios) - expected["unknown"], log)
        validate_connections(after_disconnect, after, connection_mode, 2, log)


def validate_connections(before, after, mode, expected, log):
    counts = {key: counter_delta(before, after, "_connections", key)
              for key in ("accepted", "requests", "reused")}
    if counts["requests"] != expected or counts["accepted"] + counts["reused"] != expected:
        raise RuntimeError(f"Origin request counts do not match the frontend: {counts}; see {log}")
    if (counts["reused"] > 0) != (mode == "keep-alive"):
        raise RuntimeError(f"Origin did not observe the configured {mode} behavior: {counts}; see {log}")
    return counts


def proc(process):
    cpu, peak, rss = subprocess.check_output(
        [sys.executable, str(SCRIPTS / "proc_metrics.py"), str(process.pid)], text=True,
    ).split()
    return float(cpu), int(peak), int(rss)


def load(port, rate, seconds, cpus, prefix):
    summary = prefix.with_suffix(".k6.json")
    log_path = prefix.with_suffix(".k6.log")
    env = {key: value for key, value in os.environ.items() if not key.startswith("K6_")}
    env.update({
        "GOMAXPROCS": "1",
        "UPSTREAM_FRONTEND": f"http://127.0.0.1:{port}",
        "UPSTREAM_RATE": str(rate),
        "UPSTREAM_DURATION": str(seconds),
        "UPSTREAM_SUMMARY": str(summary),
    })
    with log_path.open("w") as log:
        subprocess.run(
            [*affinity(cpus), "/usr/bin/time", "-f", "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M",
             "k6", "run", "--no-usage-report", "--quiet", "--no-color", str(SCRIPTS / "k6-upstream.js")],
            stdout=log, stderr=subprocess.STDOUT, env=env, check=True, timeout=seconds + 20,
        )
    metrics = json.loads(summary.read_text())["metrics"]
    dropped, mismatches, requests = (
        int(metrics.get(metric, {}).get("values", {}).get("count", 0))
        for metric in ("dropped_iterations", "bench_response_mismatches", "http_reqs")
    )
    counts_match = all(
        abs(metrics.get(f"bench_{name}_duration", {}).get("values", {}).get("count", 0) - rate * weight * seconds / 100)
        <= max(1, rate * weight * seconds / 10_000)
        for name, (weight, _, _) in CASES.items()
    )
    generator = read_load_timing(log_path)
    valid = dropped == mismatches == 0 and counts_match and generator["load_cpu_equivalents"] < 0.9
    return metrics, valid, dropped, mismatches, requests, generator


def measure(args, cpus, origin, origin_port, frontend, front_port, prefix, variant, client, mode, rate, repetition):
    _, _, cold_dropped, cold_mismatches, _, _ = load(
        front_port, rate, args.precondition, cpus, prefix.with_name(prefix.name + "-precondition"),
    )
    _, warmed, warm_dropped, warm_mismatches, _, _ = load(
        front_port, rate, args.warmup, cpus, prefix.with_name(prefix.name + "-warmup"),
    )
    before = origin_stats(origin_port)
    server_cpu, _, _ = proc(frontend)
    origin_cpu, _, _ = proc(origin)
    metrics, valid, dropped, mismatches, requests, generator = load(front_port, rate, args.duration, cpus, prefix)
    server_end, server_peak, _ = proc(frontend)
    origin_end, origin_peak, _ = proc(origin)
    after = origin_stats(origin_port)
    origin_log = prefix.with_suffix(".origin.json")
    origin_log.write_text(json.dumps({"before": before, "after": after}, indent=2) + "\n")
    matched = sum(int(metrics.get(f"bench_{name}_duration", {}).get("values", {}).get("count", 0)) for name in CASES)
    cpu_seconds = server_end - server_cpu
    execution = {
        "variant": variant, "offered_rate": rate, "run": repetition, "duration_s": args.duration,
        "valid": int(warmed and valid), "http_requests": requests, "dropped_arrivals": dropped,
        "response_mismatches": mismatches, "server_cpu_seconds": round(server_end - server_cpu, 6),
        "precondition_dropped_arrivals": cold_dropped, "precondition_mismatches": cold_mismatches,
        "warmup_valid": int(warmed), "warmup_dropped_arrivals": warm_dropped, "warmup_mismatches": warm_mismatches,
        "server_peak_rss_kib": server_peak, "origin_cpu_seconds": round(origin_end - origin_cpu, 6),
        "origin_peak_rss_kib": origin_peak, **generator,
    }
    if args.compare_reuse:
        connection_counts = {key: counter_delta(before, after, "_connections", key)
                             for key in ("accepted", "requests", "reused")}
        deadline_closed = counter_delta(before, after, "deadline", "peer_closed")
        deadline_replied = counter_delta(before, after, "deadline", "replied")
        deadline_starts = counter_delta(before, after, "deadline", "started")
        try:
            validate_connections(before, after, mode, matched, origin_log)
        except RuntimeError as error:
            print(str(error), flush=True)
            execution["valid"] = 0
        if deadline_closed != deadline_starts or deadline_replied:
            execution["valid"] = 0
        execution.update(
            client=client, connection_mode=mode, matched_total=matched,
            server_cpu_seconds_per_million_matched=round(cpu_seconds * 1_000_000 / matched, 3) if matched else "",
            origin_new_connections=connection_counts["accepted"], origin_reused_requests=connection_counts["reused"],
            origin_deadline_closed=deadline_closed, origin_deadline_replied=deadline_replied,
        )
    for name, (_, expected, _) in CASES.items():
        stats = metrics.get(f"bench_{name}_duration", {}).get("values", {})
        yield {
            **execution, "scenario": name, "expected_status": expected, "matched": stats.get("count", 0),
            "matched_rps": round(stats.get("count", 0) / args.duration, 3),
            **{f"p{p}_ms": stats.get(f"p({p})", "") for p in (50, 95, 99)},
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--smoke-only", action="store_true")
    parser.add_argument("--variants", nargs="+", choices=VARIANTS)
    parser.add_argument("--compare-reuse", action="store_true", help="compare fresh and reused upstream TCP connections")
    parser.add_argument("--rates", nargs="+", type=int, default=[200, 800])
    parser.add_argument("--precondition", type=int, default=15)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--duration", type=int, default=8)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if args.compare_reuse:
        by_variant = {
            "ktserve": [("cio", "fresh"), ("socket", "fresh"), ("socket", "keep-alive")],
            "rust-axum": [("hyper", "fresh"), ("hyper", "keep-alive")],
        }
        args.variants = args.variants or list(by_variant)
        if any(variant not in by_variant for variant in args.variants):
            parser.error("The reuse experiment compares ktServe and Axum")
        configs = [(variant, client, mode) for variant in args.variants for client, mode in by_variant[variant]]
    else:
        args.variants = args.variants or list(VARIANTS)
        configs = [(variant, "cio" if variant != "rust-axum" else "hyper", "fresh") for variant in args.variants]
    if (any(rate < 20 or rate % 20 for rate in args.rates)
            or min(args.precondition, args.warmup, args.duration, args.repeats) < 1):
        parser.error("Use positive durations, repetitions and offered rates divisible by 20.")
    args.output.mkdir(parents=True, exist_ok=True)
    results = args.output / "upstream.csv"
    if results.exists():
        parser.error("Use an output directory without an existing upstream.csv.")
    origin_cpus = server_cpus = load_cpus = []
    if not args.smoke_only:
        available = sorted(os.sched_getaffinity(0))
        if len(available) < 4:
            parser.error("The loopback pilot needs at least four logical CPUs.")
        server_cpus, load_cpus, origin_cpus = available[:2], available[2:3], available[3:4]
        with (args.output / "environment.txt").open("w") as environment:
            print(f"machine={platform.machine()}\nkernel={platform.release()}", file=environment)
            print(f"server_cpus={server_cpus}\nload_cpus={load_cpus}\norigin_cpus={origin_cpus}", file=environment)
            print(f"rates={args.rates} precondition={args.precondition} warmup={args.warmup} "
                  f"duration={args.duration} repeats={args.repeats}", file=environment)
            print("http_protocol=HTTP/1.1\norigin=loopback on the same host\nfrontend_deadline_ms=150", file=environment)
            print(f"configurations={configs}", file=environment)
            environment.flush()
            for command in (["git", "rev-parse", "HEAD"], ["lscpu"], ["java", "-version"], ["gradle", "--version"],
                            ["rustc", "--version"], ["k6", "version"], [sys.executable, "--version"]):
                subprocess.run(command, cwd=ROOT, stdout=environment, stderr=subprocess.STDOUT, check=True)
            binaries = {
                "ktserve": [PROBE],
                "rust-axum": [ROOT / "competitors/rust-axum/target/release/axum-baseline"],
                "ktor-native": [ROOT / "competitors/ktor/build/bin/native/releaseExecutable/ktor-baselines.kexe"],
                "ktor-jvm": sorted((ROOT / "competitors/ktor/build/jvmDist").rglob("*.jar")),
            }
            binaries = [binary for variant in args.variants for binary in binaries[variant]]
            for binary in binaries:
                with binary.open("rb") as source:
                    digest = hashlib.file_digest(source, "sha256").hexdigest()
                print(f"{digest}  {binary.relative_to(ROOT)}", file=environment)
    origin_port = open_port()
    all_valid = True
    origin_command = [sys.executable, str(SCRIPTS / "upstream-origin.py"), str(origin_port)]
    if args.compare_reuse:
        origin_command.append("--keep-alive")
    with launch(origin_command, origin_cpus, args.output / "origin.log") as origin:
        ready(origin, origin_port, "/stats")
        for repetition in range(1, (1 if args.smoke_only else args.repeats) + 1):
            for rate in ([0] if args.smoke_only else args.rates):
                for index in range(len(configs)):
                    variant, client, mode = configs[(index + repetition - 1) % len(configs)]
                    name = f"{variant}-{client}-{mode}" if args.compare_reuse else variant
                    prefix = args.output / f"{name}-{rate}-{repetition}"
                    front_port = open_port()
                    with launch(VARIANTS[variant], server_cpus, prefix.with_suffix(".server.log"), PORT=str(front_port),
                                BIND_HOST="127.0.0.1", BENCHMARK_SUITE="application-upstream",
                                BENCHMARK_UPSTREAM_PORT=str(origin_port), UPSTREAM_CONNECTION_MODE=mode,
                                UPSTREAM_CLIENT=client) as frontend:
                        ready(frontend, front_port, "/health")
                        preflight(front_port, origin_port, prefix.with_suffix(".parity.json"),
                                  mode if args.compare_reuse else None)
                        if not args.smoke_only:
                            rows = list(measure(args, load_cpus, origin, origin_port, frontend, front_port,
                                                prefix, variant, client, mode, rate, repetition))
                            append = results.exists()
                            with results.open("a", newline="") as output:
                                writer = csv.DictWriter(output, fieldnames=rows[0])
                                if not append:
                                    writer.writeheader()
                                writer.writerows(rows)
                            all_valid = all_valid and rows[0]["valid"] == 1
                            print(f"{prefix.name}: valid={bool(rows[0]['valid'])}", flush=True)
    if not all_valid:
        raise SystemExit("Invalid trials and raw logs retained in the output directory.")


if __name__ == "__main__":
    main()
