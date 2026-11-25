#!/usr/bin/env python3
"""Pair route dispatch probes on one Linux runner with separate CPU masks."""

import argparse
import hashlib
from importlib.util import module_from_spec, spec_from_file_location
import json
import os
from pathlib import Path
import platform
import re
import shlex
import socket
import subprocess
import sys

from parse_wrk import error_count, read_load_timing, read_stats


SCRIPTS = Path(__file__).resolve().parent
spec = spec_from_file_location("integration_benchmark", SCRIPTS / "benchmark-integrations.py")
mix = module_from_spec(spec)
spec.loader.exec_module(mix)
TIME = [
    "/usr/bin/time", "-f", "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M",
]


def process_metrics(pid):
    values = mix.run_output(sys.executable, SCRIPTS / "proc_metrics.py", str(pid)).split()
    return float(values[0]), int(values[1]), int(values[2])


def check_routes(port, resources):
    for resource in (0, resources // 2, resources - 1):
        for suffix, identifier in (("current", 0), ("789", 789)):
            mix.require_response(port, f"/api/v1/r{resource}/items/{suffix}", "application/json",
                                 {"resource": resource, "id": identifier})


def emit_lua(output, resources):
    stats = (SCRIPTS / "wrk-stats.lua").read_text()
    output.write_text(stats + f"""
local i = 0
function request()
  i = (i + 1) % 4096
  local resource = (i * 137 + 29) % {resources}
  local id = i % 8 == 0 and "current" or (i + 1)
  return wrk.format("GET", "/api/v1/r" .. resource .. "/items/" .. id)
end
""")


def load(args, affinity, port, resources, seconds, prefix):
    url = f"http://127.0.0.1:{port}"
    log = prefix.with_suffix(".load.log")
    env = None
    if args.rate:
        report = prefix.with_suffix(".k6.json")
        env = {key: value for key, value in os.environ.items() if not key.startswith("K6_")}
        env.update(GOMAXPROCS="2", ROUTING_RATE=str(args.rate), ROUTING_SECONDS=str(seconds),
                   ROUTING_URL=url, ROUTING_RESOURCES=str(resources), ROUTING_OUTPUT=str(report))
        client = ["k6", "run", "--quiet", "--no-color", "--no-usage-report", str(SCRIPTS / "k6-routing.js")]
    else:
        lua = args.output / f"routes-{resources}.lua"
        client = ["wrk", "-t2", f"-c{args.connections}", f"-d{seconds}s", "--timeout", "2s",
                  "--latency", "-s", str(lua), url]
    with log.open("w") as stream:
        subprocess.run([*affinity, *TIME, *client], env=env, stdout=stream, stderr=subprocess.STDOUT,
                       check=True, timeout=seconds + 20)
    timing = read_load_timing(log)
    if args.rate:
        metrics = json.loads(report.read_text())["metrics"]
        counts = {key: value["values"].get("count", 0) for key, value in metrics.items()}
        latencies = metrics.get("routing_latency", {}).get("values", {})
        successful = counts.get("routing_latency", 0)
        errors = counts.get("routing_failures", 0) + counts.get("dropped_iterations", 0)
        valid = (successful > 0 and successful == counts["http_reqs"] == counts["iterations"]
                 and errors == 0 and abs(successful - args.rate * seconds) <= args.rate * seconds / 100
                 and 0 < timing["load_cpu_equivalents"] <= 1.6)
        rps = successful / seconds
        quantiles = {f"p{p}_us": round(latencies[f"p({p})"] * 1000, 3) if successful else "" for p in (50, 95, 99)}
    else:
        stats = read_stats(log)
        successful = int(stats["requests"])
        errors = error_count(stats)
        valid = successful > 0 and errors == 0
        rps = successful * 1_000_000 / int(stats["duration_us"])
        quantiles = {f"p{p}_us": stats[f"p{p}_us"] for p in (50, 95, 99)}
    return {"valid": int(valid), "requests": successful, "errors": errors, "rps": round(rps, 3), **quantiles, **timing}


def http_trial(args, label, command, resources, run, server_affinity, load_affinity):
    base = args.output / f"{resources}-{run}-{label}"
    with socket.socket() as socket_probe:
        socket_probe.bind(("127.0.0.1", 0))
        port = socket_probe.getsockname()[1]
    server_log = base.with_suffix(".server.log")
    with server_log.open("w") as stream:
        process = subprocess.Popen([*server_affinity, *command, "http", str(resources), str(port)],
                                   stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            mix.wait_until_ready(process, port, server_log, None)
            check_routes(port, resources)
            warmup = load(args, load_affinity, port, resources, args.warmup, base.with_name(base.name + "-warmup"))
            start, _, _ = process_metrics(process.pid)
            measured = load(args, load_affinity, port, resources, args.duration, base)
            end, peak, rss = process_metrics(process.pid)
            check_routes(port, resources)
        finally:
            mix.stop_server(process, port, "routing")
    valid = warmup["valid"] and measured["valid"] and end >= start
    cpu = end - start
    mix.write_csv_row(args.output / "http.csv", {
        "variant": label, "routes": resources * 2, "run": run, "offered_rps": args.rate, **measured,
        "valid": int(valid), "warmup_valid": warmup["valid"], "cpu_seconds": round(cpu, 6),
        "cpu_seconds_per_million_requests": round(cpu * 1_000_000 / measured["requests"], 3) if valid else "",
        "peak_rss_kib": peak, "end_rss_kib": rss,
    })
    print(f"{resources * 2} routes {label} run {run}: {measured['rps']} req/s, valid={bool(valid)}", flush=True)
    return valid


def matching_trial(args, label, command, resources, run, affinity):
    path = args.output / f"{resources}-{run}-{label}.match.log"
    with path.open("w") as log:
        subprocess.run([*affinity, *TIME, *command, "match", str(resources), str(args.iterations)],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    (stats,) = (line for line in path.read_text().splitlines() if line.startswith("routes="))
    values = dict(field.split("=", 1) for field in stats.split())
    mix.write_csv_row(args.output / "match.csv", {
        "variant": label, "run": run, **values, **read_load_timing(path),
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", action="append", required=True, metavar="LABEL=COMMAND")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resources", nargs="+", type=int, default=[1, 64, 512])
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--iterations", type=int, default=80_000)
    parser.add_argument("--duration", type=int, default=6)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--connections", type=int, default=16)
    parser.add_argument("--rate", type=int, default=0, help="Fixed offered rate for k6. Zero selects wrk saturation.")
    parser.add_argument("--only", choices=("match", "http"))
    args = parser.parse_args()
    variants = [variant.split("=", 1) for variant in args.variant]
    if any(len(item) != 2 or not re.fullmatch(r"[a-z0-9-]+", item[0]) or not item[1] for item in variants):
        parser.error("Use a simple variant label and executable command")
    variants = [(label, shlex.split(command)) for label, command in variants]
    if len(set(label for label, _ in variants)) != len(variants):
        parser.error("Variant names must be unique")
    if min(*args.resources, args.repeats, args.iterations, args.duration, args.warmup) < 1 or args.rate < 0:
        parser.error("Expected positive resources, repeats, iterations, and durations")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        parser.error("Use four logical CPUs for separate server and load affinity")
    server, client = mix.cpu_prefix(cpus[:2]), mix.cpu_prefix(cpus[2:4])
    args.output.mkdir(parents=True)
    document = {"architecture": platform.machine(), "kernel": platform.release(), "cpu": mix.run_output("lscpu"),
                "server_affinity": server, "load_affinity": client, "options": vars(args), "variants": variants}
    document["os"] = Path("/etc/os-release").read_text()
    with (args.output / "environment.json").open("w") as metadata:
        json.dump(document, metadata, indent=2, default=str)
    inputs = [Path(__file__), SCRIPTS / "wrk-stats.lua", SCRIPTS / "k6-routing.js"]
    (args.output / "harness.sha256").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in inputs
    ))
    for resources in args.resources:
        if args.rate == 0 and args.only != "match":
            emit_lua(args.output / f"routes-{resources}.lua", resources)
    valid = True
    for run in range(1, args.repeats + 1):
        for index, resources in enumerate(args.resources):
            rotation = (run + index - 1) % len(variants)
            for label, command in variants[rotation:] + variants[:rotation]:
                if args.only != "http":
                    matching_trial(args, label, command, resources, run, server)
                if args.only != "match":
                    valid = http_trial(args, label, command, resources, run, server, client) and valid
    if not valid:
        raise SystemExit("Some trials missed delivery requirements. Inspect the retained data.")


if __name__ == "__main__":
    main()
