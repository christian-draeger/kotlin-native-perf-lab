#!/usr/bin/env python3
"""Describe per-run medians; do NOT pool per-run p99 into a global p99."""
import csv
from collections import defaultdict
from pathlib import Path
from statistics import median
import sys

rows=list(csv.DictReader(Path(sys.argv[1]).open(newline='')))
if not rows:
    raise SystemExit('No benchmark rows')
groups=defaultdict(list)
for row in rows:
    groups[(row['variant'], row['endpoint'])].append(row)
print('# Preliminary performance comparison')
print()
print('All values are **medians of individual runs**, not pooled request distributions.')
print('The benchmark generator runs locally; treat results as exploratory.')
print()
print('| Variant | Endpoint | Runs | RPS (median) | p99 (median, µs) | CPU s / 1M req | Peak RSS (median, MiB) |')
print('|---|---|---:|---:|---:|---:|---:|')
for (variant, endpoint),rs in sorted(groups.items()):
    def med(key): return median(float(r[key]) for r in rs)
    print(f'| {variant} | `{endpoint}` | {len(rs)} | {med("rps"):,.0f} | {med("p99_us"):,.0f} | {med("cpu_seconds_per_million_requests"):,.1f} | {med("peak_rss_kib")/1024:,.1f} |')
print()
print('See raw `wrk` output, `results.csv`, and `environment.txt` for context and failures.')
