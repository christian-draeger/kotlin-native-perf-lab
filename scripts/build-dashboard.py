#!/usr/bin/env python3
"""Generate the static Pages dashboard from versioned trial data."""
from collections import defaultdict
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
from statistics import median
import subprocess
import sys
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


def git(*args):
    return subprocess.check_output(["git", "-C", ROOT, *args], text=True)


def stats(rows):
    output = {}
    for metric, (column, divisor) in METRICS.items():
        values = [float(row[column]) / divisor for row in rows]
        output[metric] = {"median": median(values), "min": min(values), "max": max(values)}
    return output


def read_run(csv_path, note):
    folder = csv_path.parent
    with csv_path.open(newline="") as file:
        rows = list(csv.DictReader(file))
    groups = defaultdict(list)
    for row in rows:
        groups[row["endpoint"], row["variant"]].append(row)
    environment_path = folder / "environment.txt"
    environment = {}
    if environment_path.is_file():
        for line in environment_path.read_text().splitlines():
            key, separator, value = line.partition("=")
            if separator:
                environment[key] = value.strip('"')
    timestamp = datetime.strptime(folder.name, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
    result = {
        "id": folder.name,
        "date": timestamp.isoformat(),
        "folder": quote(folder.relative_to(ROOT).as_posix()),
        "os": environment.get("PRETTY_NAME"),
        "machine": environment.get("machine"),
        "local": environment.get("load_generator", "").startswith("local "),
        "sha": environment.get("git_sha"),
        "valid": sum(row["valid"] == "1" for row in rows),
        "total": len(rows),
        "parameters": {},
        "has_environment": environment_path.is_file(),
        "groups": [],
        "note": note,
    }
    for parameter in ("connections", "threads", "warmup_s", "target_duration_s"):
        result["parameters"][parameter] = sorted({int(row[parameter]) for row in rows})
    for (endpoint, variant), group in sorted(groups.items()):
        valid = [row for row in group if row["valid"] == "1"]
        result["groups"].append({
            "endpoint": endpoint,
            "variant": variant,
            "valid": len(valid),
            "total": len(group),
            "stats": stats(valid) if valid else None,
        })
    return result


def main(destination):
    notes = json.loads((ASSETS / "run-notes.json").read_text())
    repo = os.environ.get("GITHUB_REPOSITORY", "christian-draeger/kotlin-native-perf-lab")
    revision = git("rev-parse", "HEAD").strip()
    paths = git("ls-files", "-z", "--", "results/*/results.csv").split("\0")
    runs = []
    for path in filter(None, paths):
        csv_path = ROOT / path
        if csv_path.parent.parent == ROOT / "results":
            runs.append(read_run(csv_path, notes.get(csv_path.parent.name, {})))
    data = {
        "repository": f"https://github.com/{repo}",
        "revision": revision,
        "files": [name for name in ("results/toolchains.md", "results/profiles.md") if (ROOT / name).is_file()],
        "runs": sorted(runs, key=lambda run: run["date"], reverse=True),
    }
    destination.mkdir(parents=True, exist_ok=True)
    for asset in ("index.html", "styles.css", "app.js"):
        shutil.copyfile(ASSETS / asset, destination / asset)
    (destination / "data.js").write_text(
        "window.benchmarkData = " + json.dumps(data, allow_nan=False, separators=(",", ":")) + ";\n"
    )
    print(f"Built {destination} with {len(runs)} benchmark series.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 scripts/build-dashboard.py OUTPUT_DIRECTORY")
    main(Path(sys.argv[1]))
