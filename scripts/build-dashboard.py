#!/usr/bin/env python3
"""Generate the static Pages dashboard from versioned trial data."""
import argparse
from collections import defaultdict
import csv
from datetime import datetime, timezone
from functools import lru_cache
import json
import os
from pathlib import Path
import re
import shutil
from statistics import median
import subprocess
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "dashboard"
METRICS = {
    "rps": ("rps", 1),
    "p50": ("p50_us", 1000),
    "p95": ("p95_us", 1000),
    "p99": ("p99_us", 1000),
    "cpu": ("cpu_seconds_per_million_requests", 1),
    "rss": ("peak_rss_kib", 1024),
}
PARAMETERS = ("connections", "threads", "warmup_s", "target_duration_s")
EXTRA_PARAMETERS = ("offered_rate", "vus_per_scenario", "precondition_s")
INTEGRATIONS = {
    "ktor-native": "Ktor Native",
    "ktor-jvm": "Ktor JVM",
    "spring-webflux": "Spring Boot WebFlux",
}


def git(checkout, *args):
    return subprocess.check_output(["git", "-C", checkout, *args], text=True)


def stats(rows):
    output = {}
    for metric, (column, divisor) in METRICS.items():
        values = [float(row[column]) / divisor for row in rows]
        output[metric] = {"median": median(values), "min": min(values), "max": max(values)}
    return output


@lru_cache(maxsize=None)
def commit_label(sha):
    if not sha or not re.fullmatch(r"[0-9a-f]{40}", sha):
        return None
    result = subprocess.run(
        ["git", "-C", ROOT, "show", "-s", "--format=%s%n%b", sha],
        capture_output=True, text=True, check=False,
    )
    lines = result.stdout.strip().splitlines() if result.returncode == 0 else []
    if not lines:
        return None
    if lines[0].startswith("Merge pull request #"):
        return next((line for line in lines[1:] if line.strip()), lines[0])
    return lines[0]


def canonical_variant(variant):
    if variant in ("kotlin-reactor", "nativeserve"):
        return "ktserve"
    if variant.startswith("nativeserve-"):
        return "ktserve" + variant[len("nativeserve"):]
    return variant


def read_run(csv_path, note, checkout, revision):
    folder = csv_path.parent
    with csv_path.open(newline="") as file:
        rows = list(csv.DictReader(file))
    groups = defaultdict(list)
    for row in rows:
        groups[row["endpoint"], canonical_variant(row["variant"])].append(row)
    environment_path = folder / "environment.txt"
    environment = {}
    if environment_path.is_file():
        for line in environment_path.read_text().splitlines():
            if line.startswith("Model name:"):
                environment["cpu_model"] = line.split(":", 1)[1].strip()
            key, separator, value = line.partition("=")
            if separator:
                environment[key] = value.strip('"')
    ci = environment.get("benchmark_source") == "github-hosted-actions"
    suite = environment.get("benchmark_suite")
    integration = environment.get("integration_family") if suite == "integration" else None
    if suite == "integration" and integration not in INTEGRATIONS:
        raise ValueError(f"Unknown integration family in {environment_path}")
    if ci:
        architecture = environment.get("machine", "unknown")
        label = "ARM64" if architecture == "aarch64" else architecture
        note = {
            "label": f"GitHub Actions · Linux {label}",
            "kind": "Exploratory CI run",
            "host": environment.get("cpu_model"),
            "note": "The load generator and servers shared one GitHub-hosted VM. Runner CPUs and host conditions vary across series.",
        }
        if integration:
            note["label"] = f"{INTEGRATIONS[integration]} · Linux {label}"
    timestamp = datetime.strptime(folder.name.partition("-")[0], "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
    result = {
        "id": folder.name + ("-ci" if ci else ""),
        "date": timestamp.isoformat(),
        "folder": quote(folder.relative_to(checkout).as_posix()),
        "revision": revision,
        "os": environment.get("PRETTY_NAME"),
        "machine": environment.get("machine"),
        "local": environment.get("load_generator", "").startswith("local "),
        "sha": environment.get("git_sha"),
        "feature": (
            environment.get("benchmark_label") or commit_label(environment.get("git_sha"))
            or environment.get("git_subject")
        ),
        "host": environment.get("cpu_model") or note.get("host"),
        "runner": environment.get("runner_label"),
        "load_generator": environment.get("load_generator"),
        "load_tool": environment.get("load_tool", "wrk") if integration else None,
        "dirty": environment.get("git_worktree") == "dirty",
        "ci_run_id": environment.get("ci_run_id") if ci else None,
        "ci_run_attempt": environment.get("ci_run_attempt") if ci else None,
        "suite": suite,
        "integration": integration,
        "experiment": environment.get("integration_experiment"),
        "scope": environment.get("benchmark_endpoint") or None,
        "incomplete": ci and environment.get("benchmark_outcome") != "success",
        "valid": sum(row["valid"] == "1" for row in rows),
        "total": len(rows),
        "parameters": {},
        "has_environment": environment_path.is_file(),
        "groups": [],
        "note": note,
    }
    for parameter in (*PARAMETERS, *EXTRA_PARAMETERS):
        result["parameters"][parameter] = sorted({int(row[parameter]) for row in rows if row.get(parameter)})
    for (endpoint, variant), group in sorted(groups.items()):
        valid = [row for row in group if row["valid"] == "1"]
        entry = {
            "endpoint": endpoint,
            "variant": variant,
            "valid": len(valid),
            "total": len(group),
            "stats": stats(valid) if valid else None,
        }
        operations = defaultdict(list)
        for row in valid:
            for name, values in json.loads(row.get("operations") or "{}").items():
                operations[name].append(values)
        if operations:
            entry["operations"] = {
                name: {key: median(row[key] for row in values) for key in ("rps", "p99")}
                for name, values in operations.items()
            }
        result["groups"].append(entry)
    return result


def measurement_history(runs, variant=None):
    """Group valid measurements with the same host class and workload."""
    profiles = {}
    for run in sorted(runs, key=lambda entry: entry["date"]):
        if run["incomplete"] or run["dirty"] or not all(
            run[key] for key in ("machine", "host", "os", "load_generator")
        ):
            continue
        parameters = run["parameters"]
        if any(len(parameters[key]) != 1 for key in PARAMETERS):
            continue
        endpoints = sorted({
            group["endpoint"] for group in run["groups"]
            if (variant is None or group["variant"] == variant)
            and group["valid"] == group["total"] and group["stats"]
        })
        if not endpoints:
            continue
        key = (
            run["machine"], run["host"], run["os"], run["runner"], run["load_generator"],
            run["note"].get("kind"), *(parameters[name][0] for name in PARAMETERS),
        )
        if key not in profiles:
            profiles[key] = {
                "machine": run["machine"],
                "host": run["host"],
                "os": run["os"],
                "label": run["note"].get("label"),
                "parameters": {name: parameters[name][0] for name in PARAMETERS},
                "runs": [],
            }
        profiles[key]["runs"].append({"id": run["id"], "endpoints": endpoints})
    return sorted(profiles.values(), key=lambda profile: profile["runs"][-1]["id"], reverse=True)


def tracked_runs(checkout, revision, notes):
    paths = git(checkout, "ls-files", "-z", "--", "results/*/results.csv").split("\0")
    for path in filter(None, paths):
        csv_path = checkout / path
        if csv_path.parent.parent == checkout / "results":
            yield read_run(csv_path, notes.get(csv_path.parent.name, {}), checkout, revision)


def main(destination, ci_checkout):
    notes = json.loads((ASSETS / "run-notes.json").read_text())
    repo = os.environ.get("GITHUB_REPOSITORY", "christian-draeger/kotlin-native-perf-lab")
    revision = git(ROOT, "rev-parse", "HEAD").strip()
    runs = list(tracked_runs(ROOT, revision, notes))
    if ci_checkout:
        ci_revision = git(ci_checkout, "rev-parse", "HEAD").strip()
        runs.extend(tracked_runs(ci_checkout, ci_revision, {}))
    runs.sort(key=lambda run: run["date"], reverse=True)
    integration_runs = [run for run in runs if run["integration"]]
    comparison_runs = [run for run in runs if not run["integration"]]
    data = {
        "repository": f"https://github.com/{repo}",
        "revision": revision,
        "runs": comparison_runs,
        "history": measurement_history(comparison_runs, variant="ktserve"),
        "framework_history": measurement_history(comparison_runs),
        "integration_runs": integration_runs,
    }
    destination.mkdir(parents=True, exist_ok=True)
    for asset in ("index.html", "styles.css", "app.js"):
        shutil.copyfile(ASSETS / asset, destination / asset)
    if (ASSETS / "assets").is_dir():
        shutil.copytree(ASSETS / "assets", destination / "assets", dirs_exist_ok=True)
    (destination / "data.js").write_text(
        "window.benchmarkData = " + json.dumps(data, allow_nan=False, separators=(",", ":")) + ";\n"
    )
    print(f"Built {destination} with {len(runs)} benchmark series.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--ci-checkout", type=Path)
    args = parser.parse_args()
    main(args.destination, args.ci_checkout)
