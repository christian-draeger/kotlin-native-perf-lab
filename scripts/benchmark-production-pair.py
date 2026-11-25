#!/usr/bin/env python3
"""Pair two release binaries with the existing mixed HTTP capacity workload."""

import argparse
import csv
from hashlib import file_digest
from importlib.util import module_from_spec, spec_from_file_location
import json
import os
from pathlib import Path
import platform
import re
from statistics import median
import subprocess
from types import SimpleNamespace


SCRIPTS = Path(__file__).resolve().parent
spec = spec_from_file_location("integration_benchmark", SCRIPTS / "benchmark-integrations.py")
mix = module_from_spec(spec)
spec.loader.exec_module(mix)


def counters(rows):
    comparable = [row for row in rows if row["valid"] == row["generator_qualified"] == "1"]
    return {
        "qualified": len(comparable),
        "met": sum(row["slo_met"] == "1" for row in rows),
        "response_failures": sum(int(row["failed_responses"]) for row in rows),
        "unsent_arrivals": sum(int(row["dropped_iterations"]) for row in rows),
        "median_p99_ms": round(median(float(row["p99_us"]) / 1000 for row in comparable), 3) if comparable else None,
        "median_cpu_seconds_per_million": median(
            float(row["cpu_seconds_per_million_requests"]) for row in comparable
        ) if comparable else None,
        "median_peak_rss_mib": round(median(float(row["peak_rss_kib"]) / 1024 for row in comparable), 3)
        if comparable else None,
    }


def main(options):
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        raise SystemExit("Use at least four logical CPUs for separate server and load affinity")
    server_cpus, load_cpus = cpus[:2], cpus[2:4]
    server_affinity, load_affinity = mix.cpu_prefix(server_cpus), mix.cpu_prefix(load_cpus)
    variants = [
        ("reference", options.reference, options.reference_sha, "0"),
        ("candidate", options.candidate, options.candidate_sha, "0"),
    ]
    if options.observe:
        variants.append(("candidate-observed", options.candidate, options.candidate_sha, "1"))
    output = options.output
    output.mkdir(parents=True, exist_ok=False)
    for name, *_ in variants:
        (output / name).mkdir()
    calibration_dir = output / "calibration"
    calibration_dir.mkdir()
    inputs = [options.reference, options.candidate, options.calibration_server,
              SCRIPTS / "benchmark-integrations.py", SCRIPTS / "k6-integration.js", Path(__file__)]
    with (output / "input.sha256").open("w") as report:
        for file in inputs:
            with file.open("rb") as source:
                print(file_digest(source, "sha256").hexdigest(), file, file=report)
    environment = {
        "scope": "Paired exploratory same-host release measurement",
        "architecture": platform.machine(), "kernel": platform.release(),
        "cpu": subprocess.check_output(["lscpu"], text=True),
        "os": Path("/etc/os-release").read_text(),
        "k6": subprocess.check_output(["k6", "version"], text=True).strip(),
        "reference_commit": options.reference_sha, "candidate_commit": options.candidate_sha,
        "server_cpus": server_cpus, "load_cpus": load_cpus, "workers": 1,
        "rates": options.rates, "required_rate": options.required_rate, "repetitions": options.repeats,
        "precondition_s": 10, "warmup_s": 10, "duration_s": options.duration, "vus_per_operation": 96,
    }
    (output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    args = SimpleNamespace(
        family="application-mix", application_profile="capacity", load_tool="k6",
        compare_native_wakeup=False, compare_native_nodelay=False,
        rates=options.rates, vus=96, threads=2, warmup=10, precondition=10, duration=options.duration,
        executable=options.reference, output=calibration_dir, calibration_server=options.calibration_server,
    )
    calibration = mix.calibrate_traffic(args, server_affinity, load_affinity)
    for run in range(1, options.repeats + 1):
        for index, rate in enumerate(options.rates):
            rotation = (run + index - 1) % len(variants)
            for name, binary, commit, observe in variants[rotation:] + variants[:rotation]:
                args.output, args.executable = output / name, binary
                os.environ["KTSERVE_BENCHMARK_OBSERVE"] = observe
                print(f"{name} {rate} req/s trial {run}", flush=True)
                mix.trial_k6(args, commit, "ktserve", rate, run, server_affinity, load_affinity, calibration[rate])
                if observe == "1":
                    log = args.output / f"ktserve-mixed-{rate}-{run}.server.log"
                    if not re.search(r"^ktServe observed_exchanges=[1-9][0-9]*$", log.read_text(), re.MULTILINE):
                        raise SystemExit(f"Observation was requested but no exchanges were recorded in {log}")

    summary = {rate: {"calibration_qualified": calibration[rate]} for rate in options.rates}
    for name, *_ in variants:
        with (output / name / "traffic.csv").open(newline="") as data:
            rows = list(csv.DictReader(data))
        for rate in options.rates:
            trials = [row for row in rows if int(row["offered_rate"]) == rate]
            if len(trials) != options.repeats:
                raise SystemExit(f"{name} has incomplete trials at {rate} req/s")
            summary[rate][name] = counters(trials)
    report = json.dumps(summary, indent=2) + "\n"
    (output / "summary.json").write_text(report)
    print(report)
    if any(summary[options.required_rate][name]["met"] != options.repeats for name, *_ in variants):
        raise SystemExit(f"All paired variants must satisfy the service targets at {options.required_rate} req/s")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--reference-sha", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--calibration-server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rates", type=int, nargs="+", default=[800, 3200])
    parser.add_argument("--required-rate", type=int, default=800)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--duration", type=int, default=10)
    parser.add_argument("--observe", action="store_true")
    options = parser.parse_args()
    if any(rate < 20 or rate % 20 for rate in options.rates) or len(set(options.rates)) != len(options.rates):
        parser.error("Use distinct positive offered rates in multiples of 20")
    if options.required_rate not in options.rates or min(options.repeats, options.duration) < 1:
        parser.error("Include the required rate and use positive trial counts and durations")
    if not all(re.fullmatch(r"[0-9a-f]{40}", ref) for ref in (options.reference_sha, options.candidate_sha)):
        parser.error("Use full source commit SHAs")
    for field in ("reference", "candidate", "calibration_server"):
        path = getattr(options, field).resolve()
        if not path.is_file() or not os.access(path, os.X_OK):
            parser.error(f"Expected executable at {path}")
        setattr(options, field, path)
    main(options)
