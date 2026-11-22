#!/usr/bin/env python3
"""Report medians and min–max across individual valid runs."""
import csv
from collections import defaultdict
from pathlib import Path
from statistics import median
import sys


def describe(rows, key, scale=1, decimals=1):
    values = [float(row[key]) / scale for row in rows]
    return f"{median(values):,.{decimals}f} [{min(values):,.{decimals}f}–{max(values):,.{decimals}f}]"


def main():
    with Path(sys.argv[1]).open(newline="") as source:
        groups = defaultdict(list)
        for row in csv.DictReader(source):
            groups[row["variant"], row["endpoint"]].append(row)
    if not groups:
        raise SystemExit("No benchmark rows")

    print("# Preliminary performance comparison\n")
    print("Each cell shows the median [min–max] across valid runs. Latency columns summarize")
    print("the corresponding per-run percentiles. The p99 median is a summary of run-to-run")
    print("behavior and does not estimate a pooled request-level p99.\n")
    print("The HTTP/1.1 load generator shares the server host. See the environment record for limits.\n")
    print("| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    metrics = (
        ("rps", 1, 0), ("p50_us", 1, 1), ("p95_us", 1, 1), ("p99_us", 1, 1),
        ("cpu_seconds_per_million_requests", 1, 1), ("peak_rss_kib", 1024, 1),
    )
    for (variant, endpoint), group in sorted(groups.items()):
        valid = [row for row in group if row["valid"] == "1"]
        cells = [describe(valid, *metric) if valid else "—" for metric in metrics]
        print(f"| {variant} | `{endpoint}` | {len(valid)} / {len(group)} | " + " | ".join(cells) + " |")
    print("\nThe raw logs and CSV include every recorded trial. Trials with zero responses,")
    print("socket errors or wrk HTTP errors in warm-up or measurement are excluded from the medians.")


if __name__ == "__main__":
    main()
