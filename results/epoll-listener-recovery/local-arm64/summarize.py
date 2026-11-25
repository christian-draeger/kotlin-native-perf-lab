from pathlib import Path
from statistics import median
import csv
import json
import sys

root = Path(sys.argv[1])
options = json.loads((root / "environment.json").read_text())
columns = {
    "rps": (1, "successful req/s"),
    "p50_us": (1_000, "p50 ms"),
    "p95_us": (1_000, "p95 ms"),
    "p99_us": (1_000, "p99 ms"),
    "cpu_seconds_per_million_requests": (1, "CPU-s/M"),
    "peak_rss_kib": (1_024, "peak RSS MiB"),
}
labels = ("old", "candidate")


def rows_at(path):
    with path.open(newline="") as file:
        return list(csv.DictReader(file))


calibrations = {int(row["offered_rate"]): row for row in rows_at(root / "calibration/traffic-calibration.csv")}
rows = {}
for label in labels:
    trials = rows_at(root / label / "traffic.csv")
    assert all(row["git_sha"] == options["commits"][label] for row in trials)
    identities = {(int(row["offered_rate"]), int(row["run"])) for row in trials}
    assert len(identities) == len(trials) == len(options["rates"]) * options["repetitions"]
    rows[label] = {(int(row["offered_rate"]), int(row["run"])): row for row in trials}

for rate in options["rates"]:
    measured = [[rows[label][rate, trial] for trial in range(1, options["repetitions"] + 1)] for label in labels]

    def qualified(row):
        return row["generator_qualified"] == "1" and row["valid"] == "1"

    valid_pairs = [(old, after) for old, after in zip(*measured) if qualified(old) and qualified(after)]
    print(f"{rate} offered req/s, calibration {calibrations[rate]['calibration_qualified']}, "
          f"{len(valid_pairs)}/{options['repetitions']} valid pairs")
    for label, trials in zip(labels, measured):
        print(f"  {label}: "
              f"{sum(int(row['failed_responses']) for row in trials)} failed, "
              f"{sum(int(row['dropped_iterations']) for row in trials)} dropped, "
              f"{sum(row['slo_met'] == '1' for row in trials)}/{len(trials)} met all per-operation targets")
    if valid_pairs:
        for field, (divisor, text) in columns.items():
            paired = [(float(old[field]) / divisor, float(after[field]) / divisor) for old, after in valid_pairs]
            paired_percentages = [(after / old - 1) * 100 for old, after in paired if old != 0]
            averages = [median(values) for values in zip(*paired)]
            print(f"  {text}: {averages[0]:.3f} → {averages[1]:.3f}; "
                  f"paired range {min(paired_percentages):+.1f}% to {max(paired_percentages):+.1f}%")
