#!/usr/bin/env python3
"""Record one fixed-offered-load HTTP/1.1 CPU-budget trial."""

import argparse
import csv
import json
from pathlib import Path

from parse_wrk import read_load_timing


def read_report(path, planned):
    report = json.loads(path.read_text())
    attempted = report["requests"]
    ok = report["status_codes"].get("200", 0)
    valid = (
        attempted > 0
        and ok == attempted
        and not report["errors"]
        and abs(attempted - planned) * 100 <= 2 * planned
    )
    return {
        "planned": planned,
        "attempted": attempted,
        "ok": ok,
        "valid": valid,
        "latencies": report["latencies"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prefix", type=Path, help="Prefix of the paired warm-up and measured reports.")
    parser.add_argument("output", type=Path)
    parser.add_argument("variant")
    parser.add_argument("mode", choices=("persistent", "churn"))
    parser.add_argument("run", type=int)
    parser.add_argument("git_sha")
    parser.add_argument("rate", type=int)
    parser.add_argument("duration", type=int)
    parser.add_argument("warmup", type=int)
    parser.add_argument("connections", type=int)
    parser.add_argument("load_cpus", type=int)
    parser.add_argument("cpu_start", type=float)
    parser.add_argument("cpu_end", type=float)
    parser.add_argument("peak_rss", type=int)
    parser.add_argument("end_rss", type=int)
    args = parser.parse_args()

    measured = read_report(args.prefix.with_suffix(".vegeta.json"), args.rate * args.duration)
    warmup = read_report(args.prefix.with_suffix(".warmup.vegeta.json"), args.rate * args.warmup)
    cpu = args.cpu_end - args.cpu_start
    if cpu < 0:
        raise ValueError(f"CPU counter moved backwards for {args.prefix}")
    valid = measured["valid"] and warmup["valid"]
    row = {
        "variant": args.variant,
        "endpoint": "/json",
        "run": args.run,
        "valid": int(valid),
        "load_tool": "vegeta",
        "load_mode": args.mode,
        "offered_rps": args.rate,
        "planned_requests": measured["planned"],
        "attempted_requests": measured["attempted"],
        "attempted_rps": round(measured["attempted"] / args.duration, 3),
        "requests": measured["ok"],
        "rps": round(measured["ok"] / args.duration, 3),
        "failed_responses": measured["attempted"] - measured["ok"],
        "warmup_planned_requests": warmup["planned"],
        "warmup_attempted_requests": warmup["attempted"],
        "warmup_failed_responses": warmup["attempted"] - warmup["ok"],
        "p50_us": measured["latencies"]["50th"] / 1000,
        "p95_us": measured["latencies"]["95th"] / 1000,
        "p99_us": measured["latencies"]["99th"] / 1000,
        "cpu_seconds": round(cpu, 6),
        "cpu_seconds_per_million_requests": round(cpu / measured["ok"] * 1_000_000, 3) if valid else "",
        "peak_rss_kib": args.peak_rss,
        "end_rss_kib": args.end_rss,
        "connections": args.connections,
        "load_cpus": args.load_cpus,
        "target_duration_s": args.duration,
        "warmup_s": args.warmup,
        "git_sha": args.git_sha,
        **read_load_timing(args.prefix.with_suffix(".client.txt")),
    }
    new_file = not args.output.exists()
    with args.output.open("a", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=row)
        if new_file:
            writer.writeheader()
        writer.writerow(row)
    print(f"{args.variant} (/json) planned={row['planned_requests']} attempted={row['attempted_requests']} "
          f"http_200={row['requests']} p99={row['p99_us']:.1f}us valid={valid}", flush=True)


if __name__ == "__main__":
    main()
