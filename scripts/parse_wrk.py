#!/usr/bin/env python3
"""Append one measured run from wrk's end-of-run Lua output."""
import argparse
import csv
import re
from pathlib import Path


def read_stats(path):
    lines = [line for line in path.read_text().splitlines() if line.startswith("perflab ")]
    if len(lines) != 1:
        raise ValueError(f"Expected one perflab statistics line in {path}")
    return dict(field.split("=", 1) for field in lines[0].split()[1:])


def error_count(stats):
    return sum(int(stats[key]) for key in ("connect", "read", "write", "timeout", "http_errors"))


def read_eager_worker_count(path):
    records = [line for line in path.read_text().splitlines() if line.startswith("ktServe worker_policy=eager")]
    if len(records) != 1 or not (match := re.fullmatch(r"ktServe worker_policy=eager workers=([12])", records[0])):
        raise ValueError(f"Expected one Eager startup record with its effective worker count in {path}")
    return int(match.group(1))


def read_load_timing(path):
    lines = [line for line in path.read_text().splitlines() if line.startswith("perflab_load ")]
    if not lines:
        return {"load_cpu_seconds": "", "load_peak_rss_kib": "", "load_cpu_equivalents": ""}
    if len(lines) != 1:
        raise ValueError(f"Expected at most one load timing line in {path}")
    timing = dict(field.split("=", 1) for field in lines[0].split()[1:])
    cpu = float(timing["user_s"]) + float(timing["system_s"])
    elapsed = float(timing["elapsed_s"])
    return {
        "load_cpu_seconds": round(cpu, 3),
        "load_peak_rss_kib": int(timing["peak_rss_kib"]),
        "load_cpu_equivalents": round(cpu / elapsed, 3) if elapsed > 0 else "",
    }


def main():
    parser = argparse.ArgumentParser()
    for key in ("log", "warmup-log", "csv"):
        parser.add_argument("--" + key, type=Path, required=True)
    for key in ("variant", "endpoint", "git-sha"):
        parser.add_argument("--" + key, required=True)
    for key in ("run", "connections", "threads", "duration", "warmup", "peak-rss-kib", "rss-kib"):
        parser.add_argument("--" + key, type=int, required=True)
    for key in ("cpu-start", "cpu-end"):
        parser.add_argument("--" + key, type=float, required=True)
    parser.add_argument("--warmup-stability", choices=("unchecked", "stable", "unstable"), default="unchecked")
    parser.add_argument("--server-log", type=Path)
    args = parser.parse_args()
    eager_workers = ""
    if args.variant == "ktserve-eager":
        if not args.server_log:
            parser.error("ktserve-eager requires its server startup log")
        eager_workers = read_eager_worker_count(args.server_log)

    stats = read_stats(args.log)
    warmup = read_stats(args.warmup_log)
    requests = int(stats["requests"])
    duration = int(stats["duration_us"]) / 1_000_000
    cpu = args.cpu_end - args.cpu_start
    if duration <= 0 or cpu < 0:
        raise ValueError(f"Invalid duration or CPU counters in {args.log}")
    valid = (
        requests > 0
        and int(warmup["requests"]) > 0
        and error_count(stats) == error_count(warmup) == 0
        and args.warmup_stability != "unstable"
    )
    row = {
        "variant": args.variant,
        "effective_workers": eager_workers,
        "endpoint": args.endpoint,
        "run": args.run,
        "valid": int(valid),
        "requests": requests,
        "rps": round(requests / duration, 3),
        "p50_us": stats["p50_us"],
        "p95_us": stats["p95_us"],
        "p99_us": stats["p99_us"],
        "cpu_start_seconds": args.cpu_start,
        "cpu_end_seconds": args.cpu_end,
        "cpu_seconds": round(cpu, 6),
        "cpu_seconds_per_million_requests": round(cpu / requests * 1_000_000, 3) if valid else "",
        "peak_rss_kib": args.peak_rss_kib,
        "end_rss_kib": args.rss_kib,
        "connect_errors": stats["connect"],
        "read_errors": stats["read"],
        "write_errors": stats["write"],
        "timeouts": stats["timeout"],
        "http_errors": stats["http_errors"],
        "warmup_errors": error_count(warmup),
        "threads": args.threads,
        "connections": args.connections,
        "target_duration_s": args.duration,
        "duration_s": duration,
        "warmup_s": args.warmup,
        "warmup_stability": args.warmup_stability,
        "git_sha": args.git_sha,
        **read_load_timing(args.log),
    }
    new_file = not args.csv.exists()
    with args.csv.open("a", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=row)
        if new_file:
            writer.writeheader()
        writer.writerow(row)
    print(f"{args.variant:12s} {args.endpoint:12s} run={args.run} valid={valid} "
          f"rps={row['rps']:.1f} p99={float(row['p99_us']):.1f}us cpu={cpu:.2f}s "
          f"peak_rss={args.peak_rss_kib}KiB")


if __name__ == "__main__":
    main()
