"""Check throughput and compilation activity across three wrk windows."""

import argparse
from decimal import Decimal
from pathlib import Path

from parse_wrk import error_count, read_stats


def hot_spot_stats(path):
    header, row = path.read_text().splitlines()
    stats = dict(zip(header.split(), row.split()))
    return Decimal(stats["Timestamp"]), Decimal(stats["Time"]), int(stats["Compiled"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("windows", nargs=3, type=Path)
    parser.add_argument("--compiler", nargs=4, type=Path, help="Snapshots at the four boundaries of three windows")
    args = parser.parse_args()
    windows = [read_stats(path) for path in args.windows]
    durations = [int(stats["duration_us"]) for stats in windows]
    if any(duration <= 0 for duration in durations):
        raise ValueError("Expected positive wrk window durations")
    rates = [int(stats["requests"]) * 1_000_000 / duration for stats, duration in zip(windows, durations)]
    spread = (max(rates) / min(rates) - 1) * 100 if min(rates) else float("inf")
    snapshots = [hot_spot_stats(path) for path in args.compiler or []]
    compilation_percent = []
    for (before, time_before, _), (after, time_after, _) in zip(snapshots, snapshots[1:]):
        if after <= before or time_after < time_before:
            raise ValueError("Expected monotonic HotSpot compiler snapshots from one process")
        compilation_percent.append(100 * (time_after - time_before) / (after - before))
    stable_compiler = all(percent <= 1 for percent in compilation_percent)
    stable = spread <= 10 and stable_compiler and all(error_count(stats) == 0 for stats in windows)
    compiled = [count for _, _, count in snapshots]
    percentages = [round(float(percent), 2) for percent in compilation_percent]
    print(f"{'stable' if stable else 'unstable'} throughput_spread_pct={spread:.2f} "
          f"compiler_time_pct={percentages} compiled={compiled}")


if __name__ == "__main__":
    main()
