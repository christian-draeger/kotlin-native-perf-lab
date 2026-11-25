import csv
from pathlib import Path
from statistics import median
import sys

output = Path(sys.argv[1])
with (output / "calibration/traffic-calibration.csv").open(newline="") as source:
    calibration = list(csv.DictReader(source))
for trial in calibration:
    print("Calibration", trial["offered_rate"], trial["calibration_qualified"], "generator CPUs", trial["load_cpu_equivalents"])
assert {int(row["offered_rate"]) for row in calibration} == {200, 800}
for variant in ("old", "candidate"):
    with (output / variant / "traffic.csv").open(newline="") as source:
        rows = list(csv.DictReader(source))
    assert {(row["offered_rate"], row["run"]) for row in rows} == {
        (str(rate), str(run)) for rate in (200, 800) for run in (1, 2)
    }
    print(variant)
    for rate in (200, 800):
        trials = [row for row in rows if int(row["offered_rate"]) == rate]
        for trial in trials:
            print(" ", rate, "#" + trial["run"], f"rps={trial['rps']}", f"p50={float(trial['p50_us'])/1000:.3f}ms",
                  f"p95={float(trial['p95_us'])/1000:.3f}ms", f"p99={float(trial['p99_us'])/1000:.3f}ms",
                  f"cpu={trial['cpu_seconds']}s", f"peak={float(trial['peak_rss_kib'])/1024:.2f}MiB",
                  f"failures={trial['failed_responses']}", f"unsent={trial['dropped_iterations']}",
                  f"generator_qualified={trial['generator_qualified']}", f"valid={trial['valid']}",
                  f"target_met={trial['slo_met']}", f"accounted={trial['accounted']}",
                  f"cold_failures={trial['precondition_failed_responses']}", f"warmup_drops={trial['warmup_dropped_iterations']}")
        for key, scale in (("successful_responses", 1), ("p99_us", 1000), ("cpu_seconds", 1), ("peak_rss_kib", 1024)):
            print("    median", key, f"{median(float(row[key]) for row in trials)/scale:.3f}")
