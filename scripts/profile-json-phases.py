#!/usr/bin/env python3
"""Measure Kotlin/Native JSON stages without an HTTP server or load generator."""

import argparse
import csv
import hashlib
import json
import os
import subprocess
from pathlib import Path


def fixture_bytes(executable, cpu, payload, expected):
    actual = subprocess.check_output(["taskset", "-c", cpu, executable, payload, "fixture"])
    reference = json.dumps(expected, ensure_ascii=False, separators=(",", ":")).encode()
    if actual != reference:
        raise ValueError(f"{payload} serialized fixture did not match the reference bytes")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=lambda path: Path(path).resolve())
    parser.add_argument("heap_executable", type=lambda path: Path(path).resolve())
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    args.output.mkdir(parents=True, exist_ok=False)
    cpu = str(min(os.sched_getaffinity(0)))
    fixtures = {
        "small": {"id": 42, "name": "benchmark-user", "active": True},
        "catalog": {
            "id": 123,
            "items": [
                {"sku": f"123-{index}", "name": f"Café item {index}",
                 "description": "Roasted in München and packed for delivery.",
                 "stock": {"available": index > 0, "units": index * 3}}
                for index in range(32)
            ],
        },
    }
    phases = ("construct", "string", "utf8", "complete")
    with (args.output / "environment.txt").open("w") as metadata:
        metadata.write("benchmark_source=github-hosted-or-local-diagnostic\n")
        metadata.write("measurement=isolated Kotlin/Native phases, no HTTP load\n")
        metadata.write(f"cpu_affinity={cpu}\n")
        executables = {"timing": args.executable, "heap": args.heap_executable}
        for mode, executable in executables.items():
            with executable.open("rb") as binary:
                digest = hashlib.file_digest(binary, "sha256").hexdigest()
            metadata.write(f"{mode}_release_sha256={digest}\n")
        for command in (["uname", "-a"], ["lscpu"], ["git", "-C", root, "rev-parse", "HEAD"]):
            metadata.write(subprocess.check_output(command, text=True))
        for payload, expected in fixtures.items():
            data = fixture_bytes(args.executable, cpu, payload, expected)
            fixture_bytes(args.heap_executable, cpu, payload, expected)
            metadata.write(f"{payload}_bytes={len(data)}\n")
            metadata.write(f"{payload}_sha256={hashlib.sha256(data).hexdigest()}\n")

    with (args.output / "json-phases.csv").open("w", newline="") as results:
        writer = csv.DictWriter(results, fieldnames=[
            "payload", "phase", "repetition", "mode", "iterations", "wall_ns", "cpu_ns",
            "completed_gc_cycles", "managed_heap_growth_bytes", "peak_rss_kib",
        ])
        writer.writeheader()
        for payload in fixtures:
            for repetition in range(5):
                offset = repetition % len(phases)
                for phase in phases[offset:] + phases[:offset]:
                    for mode, iterations in (("timing", 50_000), ("heap", 1_024)):
                        stem = args.output / f"{payload}-{phase}-{repetition + 1}-{mode}"
                        rss = stem.with_suffix(".rss")
                        with stem.with_suffix(".stderr").open("w") as errors:
                            sample = subprocess.check_output(
                                ["taskset", "-c", cpu, "/usr/bin/time", "-f", "%M", "-o", rss,
                                 executables[mode], payload, phase, mode, str(iterations)],
                                text=True, stderr=errors,
                            )
                        stem.with_suffix(".stdout").write_text(sample)
                        values = dict(entry.split("=", 1) for entry in sample.strip().split(","))
                        writer.writerow({
                            "payload": payload, "phase": phase, "repetition": repetition + 1,
                            "mode": mode, "iterations": iterations, "peak_rss_kib": int(rss.read_text()),
                            **values,
                        })
                        results.flush()


if __name__ == "__main__":
    main()
