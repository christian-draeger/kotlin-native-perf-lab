import csv
from pathlib import Path
from statistics import median
import sys

results = Path(sys.argv[1])
rows = list(csv.DictReader((results / "results.csv").open(newline="")))
assert len(rows) == 20, len(rows)
metrics = {
    "rps": "requests/s",
    "p99_us": "p99 (µs)",
    "cpu_seconds_per_million_requests": "CPU s / M requests",
    "peak_rss_kib": "Peak RSS (KiB)",
}
for endpoint in ("/health", "/json"):
    subset = [row for row in rows if row["endpoint"] == endpoint]
    assert len(subset) == 10
    print(endpoint)
    pairs = [{row["variant"]: row for row in subset if int(row["run"]) == number} for number in range(1, 6)]
    assert all(set(pair) == {"old", "candidate"} for pair in pairs)
    valid = [pair for pair in pairs if all(row["valid"] == "1" for row in pair.values())]
    print(f"{len(valid)} / {len(pairs)} valid pairs")
    for key, title in metrics.items():
        old = [float(pair["old"][key]) for pair in valid]
        new = [float(pair["candidate"][key]) for pair in valid]
        deltas = [(right / left - 1) * 100 for left, right in zip(old, new, strict=True)]
        print(f"{title} | {median(old):.2f} | {median(new):.2f} | {median(deltas):+.2f}% "
              f"[{min(deltas):+.2f}%, {max(deltas):+.2f}%]")
    print("Maximum load CPU equivalents", max(float(row["load_cpu_equivalents"]) for row in subset))
