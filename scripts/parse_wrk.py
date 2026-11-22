#!/usr/bin/env python3
"""Append one measured run from wrk's end-of-run Lua output."""
import argparse
import csv
from pathlib import Path


def read_stats(path):
    lines = [line for line in path.read_text().splitlines() if line.startswith("perflab ")]
    if len(lines) != 1:
        raise ValueError(f"Expected one perflab statistics line in {path}")
    return dict(field.split("=", 1) for field in lines[0].split()[1:])


def error_count(stats):
    return sum(int(stats[key]) for key in ("connect", "read", "write", "timeout", "http_errors"))


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
    args = parser.parse_args()

    stats = read_stats(args.log)
    warmup = read_stats(args.warmup_log)
    requests = int(stats["requests"])
    duration = int(stats["duration_us"]) / 1_000_000
    cpu = args.cpu_end - args.cpu_start
    if duration <= 0 or cpu < 0:
        raise ValueError(f"Invalid duration or CPU counters in {args.log}")
    valid = requests > 0 and int(warmup["requests"]) > 0 and error_count(stats) == error_count(warmup) == 0
    row = {
        "variant": args.variant,
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
        "git_sha": args.git_sha,
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
