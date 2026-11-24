#!/usr/bin/env python3
"""Measure a continuous fixed-arrival HTTP/1.1 mix with two load steps on Linux."""

import argparse
import csv
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import signal
import socket
import subprocess
import sys
import time
from contextlib import contextmanager, suppress


ROOT = Path(__file__).resolve().parents[1]
BINARIES = {
    "ktserve": ROOT / "components/ktserve/build/bin/native/releaseExecutable/ktserve.kexe",
    "rust-axum": ROOT / "competitors/rust-axum/target/release/axum-baseline",
}
PROFILE = (
    ("warmup", 1, 800), ("steady", 10, 800), ("burst", 2, 1600),
    ("recovery", 6, 800), ("burst", 2, 3200), ("recovery", 10, 800),
)


@contextmanager
def process(command, env, log):
    with log.open("w") as output, subprocess.Popen(
        command, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
    ) as child:
        try:
            yield child
        finally:
            if child.poll() is None:
                with suppress(ProcessLookupError):
                    os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    with suppress(ProcessLookupError):
                        os.killpg(child.pid, signal.SIGKILL)
                    child.wait()


def wait_for_server(server, port):
    deadline = time.monotonic() + 20
    while server.poll() is None and time.monotonic() < deadline:
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            if response.status == 200 and response.read() == b"ok":
                return
        except OSError:
            pass
        finally:
            connection.close()
        time.sleep(0.1)
    raise RuntimeError("Server failed readiness. See server.log.")


def sample_process(pid):
    proc = Path("/proc") / str(pid)
    try:
        fields = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
        status = (proc / "status").read_text().splitlines()
    except FileNotFoundError:
        return {}
    rss = {line.split(":", 1)[0]: int(line.split()[1])
           for line in status if line.startswith(("VmHWM:", "VmRSS:"))}
    return {
        "cpu_s": (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK"),
        "rss_kib": rss.get("VmRSS"),
        "peak_rss_kib": rss.get("VmHWM"),
    }


def monitor(load, server, output, interval):
    samples = []
    with output.open("w", newline="") as handle:
        metrics = ("cpu_s", "rss_kib", "peak_rss_kib")
        writer = csv.DictWriter(
            handle, fieldnames=["epoch_ms"] + [f"{kind}_{key}" for kind in ("server", "load") for key in metrics],
        )
        writer.writeheader()
        while True:
            row = {"epoch_ms": time.time_ns() // 1_000_000}
            for kind, child in (("server", server), ("load", load)):
                row.update({f"{kind}_{key}": value for key, value in sample_process(child.pid).items()})
            writer.writerow(row)
            handle.flush()
            samples.append(row)
            if load.poll() is not None:
                return samples
            try:
                load.wait(timeout=interval)
            except subprocess.TimeoutExpired:
                pass


def write_windows(summary, samples, phases, seconds, output):
    metrics = summary["metrics"]

    def values(metric):
        return metrics.get(metric, {}).get("values", {})

    started_at = int(values("perflab_endurance_start_epoch_ms")["value"])
    rows = []
    for phase in phases:
        for _ in range(phase["seconds"] // seconds):
            window = len(rows)
            tag = f"{{endurance_window:{window}}}"
            start = started_at + window * seconds * 1000
            end = start + seconds * 1000
            latency = values(f"perflab_success_duration{tag}")
            started = int(values(f"perflab_endurance_starts{tag}").get("count", 0))
            success = int(latency.get("count", 0))
            failed = int(values(f"perflab_failed_responses{tag}").get("count", 0))
            offered = phase["rate"] * seconds
            before, after = (
                min(samples, key=lambda row: abs(row["epoch_ms"] - boundary)) for boundary in (start, end)
            )
            cpu_before, cpu_after = before.get("server_cpu_s"), after.get("server_cpu_s")
            rows.append({
                "window": window,
                "phase": phase["name"],
                "start_elapsed_s": window * seconds,
                "window_s": seconds,
                "offered_rps": phase["rate"],
                "offered": offered,
                "started": started,
                "successful": success,
                "failed": failed,
                "scheduled_minus_started": offered - started,
                "achieved_successful_rps": round(success / seconds, 3),
                "p50_ms": latency.get("p(50)"),
                "p95_ms": latency.get("p(95)"),
                "p99_ms": latency.get("p(99)"),
                "cpu_sample_window_s": (after["epoch_ms"] - before["epoch_ms"]) / 1000,
                "server_cpu_s": (
                    round(cpu_after - cpu_before, 6) if cpu_before is not None and cpu_after is not None else None
                ),
                "server_end_rss_kib": after.get("server_rss_kib"),
                "server_peak_since_start_kib": after.get("server_peak_rss_kib"),
            })
    with output.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    actual_starts = int(values("perflab_endurance_starts")["count"])
    actual_successes = int(values("perflab_success_duration")["count"])
    actual_failures = int(values("perflab_failed_responses")["count"])
    dropped = int(values("dropped_iterations").get("count", 0))
    for field, count in (("started", actual_starts), ("successful", actual_successes), ("failed", actual_failures)):
        if sum(row[field] for row in rows) != count:
            raise RuntimeError(f"Window accounting disagrees with the run total for {field}. See raw summary.")
    print(f"{len(rows)} windows, {actual_starts:,} starts, {actual_successes:,} successes, "
          f"{actual_failures:,} failures, {dropped:,} k6 dropped iterations.", flush=True)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("variant", choices=BINARIES)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--smoke", action="store_true", help="Use twelve seconds at reduced rates")
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("the benchmark requires Linux process metrics")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        parser.error("the benchmark needs four available logical CPUs")
    server_cpus, load_cpus = [",".join(map(str, part)) for part in (cpus[:2], cpus[2:4])]
    seconds = 2 if args.smoke else 60
    profile = [
        {"name": name, "seconds": (1 if args.smoke else windows) * seconds, "rate": rate // (10 if args.smoke else 1)}
        for name, windows, rate in PROFILE
    ]
    output = args.output.absolute()
    output.mkdir(parents=True)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    env = {
        **{key: val for key, val in os.environ.items() if not key.startswith(("K6_", "KTSERVE_EPOLL_"))},
        "BIND_HOST": "127.0.0.1", "PORT": str(port), "BENCHMARK_SUITE": "application-mix",
        "KTSERVE_WORKERS": "1", "KTSERVE_BACKEND": "epoll", "TOKIO_WORKER_THREADS": "2",
        "GOMAXPROCS": "2", "PERFLAB_TARGET": base, "PERFLAB_APPLICATION_MIX": "true",
        "PERFLAB_APPLICATION_PROFILE": "persistent", "PERFLAB_RATE": str(profile[0]["rate"]),
        "PERFLAB_DURATION": str(seconds), "PERFLAB_VUS": "8" if args.smoke else "48",
        "PERFLAB_SUMMARY": str(output / "k6-summary.json"),
        "PERFLAB_ENDURANCE_PHASES": json.dumps(profile), "PERFLAB_ENDURANCE_WINDOW_SECONDS": str(seconds),
    }
    with BINARIES[args.variant].open("rb") as binary:
        binary_sha = hashlib.file_digest(binary, "sha256").hexdigest()
    with (output / "environment.txt").open("w") as metadata:
        metadata.write("\n".join((
            f"variant={args.variant}", f"arch={platform.machine()}", f"kernel={platform.release()}",
            f"server_cpus={server_cpus}", f"load_cpus={load_cpus}", f"profile={json.dumps(profile)}",
            f"binary_sha256={binary_sha}", f"preallocated_vus_per_operation={env['PERFLAB_VUS']}",
            f"target_release={'smoke' if args.smoke else 'endurance'}",
            "scope=exploratory, server and load share one test host with disjoint CPU affinity",
            "comparison=hosted variants run in separate jobs and may use different CPUs",
            f"image_os={os.getenv('ImageOS', '')}", f"image_version={os.getenv('ImageVersion', '')}",
            f"ci_run_id={os.getenv('GITHUB_RUN_ID', '')}", f"ci_run_attempt={os.getenv('GITHUB_RUN_ATTEMPT', '')}",
            "ktserve_workers=1", "axum_tokio_worker_threads=2",
        )) + "\n")
        for command in (("git", "-C", str(ROOT), "rev-parse", "HEAD"), ("k6", "version"), ("lscpu",),
                        ("cat", "/etc/os-release")):
            metadata.write(subprocess.check_output(command, text=True))
    server_command = ["taskset", "-c", server_cpus, str(ROOT / "scripts/run-server.sh"), args.variant]
    load_command = [
        "taskset", "-c", load_cpus, "k6", "run", "--no-usage-report", "--quiet", "--no-color",
        str(ROOT / "scripts/k6-endurance.js"),
    ]
    with process(server_command, env, output / "server.log") as server:
        wait_for_server(server, port)
        with (output / "preflight.log").open("w") as preflight:
            subprocess.run([ROOT / "scripts/smoke.sh", base], env=env,
                           stdout=preflight, stderr=subprocess.STDOUT, check=True)
        with process(load_command, env, output / "k6.log") as load:
            samples = monitor(load, server, output / "process-samples.csv", 1 if args.smoke else 5)
            if load.returncode != 0:
                raise RuntimeError(f"k6 exited with {load.returncode}. See k6.log and process-samples.csv.")
        summary = json.loads((output / "k6-summary.json").read_text())
        rows = write_windows(summary, samples, profile, seconds, output / "windows.csv")
        if args.smoke and any(row["failed"] or not row["successful"] for row in rows):
            raise RuntimeError("Smoke test had failed responses or an empty window.")
        with (output / "postflight.log").open("w") as postflight:
            subprocess.run([ROOT / "scripts/smoke.sh", base], env=env,
                           stdout=postflight, stderr=subprocess.STDOUT, check=True)


if __name__ == "__main__":
    main()
