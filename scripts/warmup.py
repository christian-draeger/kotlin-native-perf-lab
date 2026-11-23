"""Check throughput and compilation activity across three wrk windows."""

import argparse
from pathlib import Path

from parse_wrk import error_count, read_stats


def hot_spot_compiled(path):
    header, row = path.read_text().splitlines()
    index = header.split().index("Compiled")
    return int(row.split()[index])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("windows", nargs=3, type=Path)
    parser.add_argument("--compiler", nargs=3, type=Path)
    args = parser.parse_args()
    windows = [read_stats(path) for path in args.windows]
    durations = [int(stats["duration_us"]) for stats in windows]
    if any(duration <= 0 for duration in durations):
        raise ValueError("Expected positive wrk window durations")
    rates = [int(stats["requests"]) * 1_000_000 / duration for stats, duration in zip(windows, durations)]
    spread = (max(rates) / min(rates) - 1) * 100 if min(rates) else float("inf")
    compiled = [hot_spot_compiled(path) for path in args.compiler] if args.compiler else []
    stable_compiler = not compiled or compiled[0] == compiled[1] == compiled[2]
    stable = spread <= 10 and stable_compiler and all(error_count(stats) == 0 for stats in windows)
    print(f"{'stable' if stable else 'unstable'} throughput_spread_pct={spread:.2f} compiled={compiled}")


if __name__ == "__main__":
    main()
