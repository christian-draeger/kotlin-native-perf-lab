#!/usr/bin/env python3
"""Run the existing k6 application mix against Linux worker policies."""

import argparse
from importlib.util import module_from_spec, spec_from_file_location
import json
import os
from pathlib import Path
import subprocess


SCRIPTS = Path(__file__).resolve().parent
spec = spec_from_file_location("integration_benchmark", SCRIPTS / "benchmark-integrations.py")
mix = module_from_spec(spec)
spec.loader.exec_module(mix)

MODES = {
    "fixed-1": ("1", "0", "0"),
    "fixed-2": ("2", "0", "0"),
    "eager": ("1", "eager", "0"),
    "standby": ("1", "standby", "1"),
    "adaptive-handoff": ("1", "1", "1"),
}
RATE = 800


def run(args):
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        raise SystemExit("Use separate two-CPU masks for the server and k6")
    server_cpus, load_cpus = cpus[:2], cpus[2:4]
    server_affinity, load_affinity = mix.cpu_prefix(server_cpus), mix.cpu_prefix(load_cpus)
    args.output.mkdir(parents=True, exist_ok=False)
    metadata = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "kernel": os.uname().release, "architecture": os.uname().machine, "cpu": mix.run_output("lscpu"),
        "k6": mix.run_output("k6", "version"), "server_cpus": server_cpus, "load_cpus": load_cpus,
        "offered_rate": RATE, "warmup_s": 3, "duration_s": 8, "vus_per_operation": args.vus,
    }
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")

    rows = []
    previous = os.environ.copy()
    for key in previous:
        if key.startswith("KTSERVE_"):
            os.environ.pop(key)
    try:
        os.environ.update(KTSERVE_WORKER_LISTENER="shared", KTSERVE_EPOLL_APPLICATION_WAKEUP="eventfd")
        names = list(MODES)
        for repeat in range(args.repetitions):
            start = repeat % len(names)
            for name in names[start:] + names[:start]:
                workers, policy, handoff = MODES[name]
                os.environ.update(KTSERVE_WORKERS=workers, KTSERVE_ADAPTIVE_WORKERS=policy, KTSERVE_ADAPTIVE_HANDOFF=handoff)
                path = args.output / f"{name}-{repeat + 1}"
                log = path.with_suffix(".server.log")
                with mix.server(args, "ktserve", server_affinity, log) as (process, port):
                    warmup = mix.load_k6(args, load_affinity, port, RATE, 3, path.with_name(path.name + "-warmup"))
                    before, _, _ = mix.run_output("python3", SCRIPTS / "proc_metrics.py", str(process.pid)).split()
                    row = mix.load_k6(args, load_affinity, port, RATE, 8, path)
                    after, peak, rss = mix.run_output("python3", SCRIPTS / "proc_metrics.py", str(process.pid)).split()
                events = log.read_text().splitlines()
                expected = "ktServe backend=epoll tcp=ipv4"
                if name in ("fixed-2", "eager"):
                    expected += " workers=2"
                elif policy != "0":
                    expected += " workers=adaptive"
                selected = expected in events and (policy not in ("eager", "standby") or
                                                    f"ktServe adaptive worker_policy={policy}" in events)
                passed = bool(selected and warmup["valid"] and row["valid"] and
                              mix.generator_headroom(row, args.threads) and mix.traffic_p99_within_budgets(row))
                result = {
                    "mode": name, "repetition": repeat + 1, "pass": passed,
                    "cpu_seconds": round(float(after) - float(before), 3), "peak_rss_kib": int(peak), "rss_kib": int(rss),
                    **row, "operations": json.loads(row["operations"]),
                }
                rows.append(result)
                print(f"{name} {repeat + 1}: {row['rps']} req/s, p99 {row['p99_us']} us, pass={passed}", flush=True)
    finally:
        os.environ.clear()
        os.environ.update(previous)
    (args.output / "mixed-results.json").write_text(json.dumps(rows, indent=2) + "\n")
    if not all(row["pass"] for row in rows):
        raise SystemExit("A worker policy missed the application mix delivery or per-operation targets")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--repetitions", type=int, default=3)
    args = parser.parse_args()
    args.executable = args.executable.resolve()
    if not os.access(args.executable, os.X_OK) or args.repetitions < 1:
        parser.error("Use a release executable and a positive repetition count")
    args.family, args.application_profile, args.load_tool = "application-mix", "persistent", "k6"
    args.compare_native_wakeup = args.compare_native_nodelay = False
    args.vus, args.threads = 24, 2
    run(args)
