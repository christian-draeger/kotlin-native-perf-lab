#!/usr/bin/env python3
"""Compare ktServe extension modes at fixed HTTP/1.1 rates on Linux."""

import argparse
import base64
import csv
import hashlib
from http.client import HTTPConnection
from importlib.util import module_from_spec, spec_from_file_location
from ipaddress import IPv4Address
import json
import os
from pathlib import Path
import platform
import re
import socket
from statistics import median
import subprocess
import sys
import time


SCRIPTS = Path(__file__).resolve().parent
spec = spec_from_file_location("ktserve_fixed_rate", SCRIPTS / "benchmark-access-log.py")
fixed = module_from_spec(spec)
sys.modules[spec.name] = fixed
spec.loader.exec_module(fixed)

MODES = ("none", "noop", "bearer", "cors", "validation", "openapi", "access-log", "all")
JVM_MODES = ("otel-unsampled", "otel-sampled")
ENDPOINT = "/api/tasks/preview"
BODY = b'{"title":"Review design"}'
HEADERS = {"Content-Type": "application/json", "Authorization": "Bearer taskboard-demo",
           "Origin": "http://localhost:5173"}
REQUEST = {"method": "POST", "header": {key: [value] for key, value in HEADERS.items()},
           "body": base64.b64encode(BODY).decode()}
COMPLETED = re.compile(rb"event=exchange method=POST status=200 duration_ns=[0-9]+ outcome=COMPLETED\n")
MEASUREMENTS = ("startup_to_response_ms", "ready_rss_kib", "peak_rss_kib", "completed_rps", "p50_us",
                "p95_us", "p99_us", "cpu_seconds_per_million")


def ready(server, host, port, mode):
    deadline = time.monotonic() + 10
    while server.poll() is None and time.monotonic() < deadline:
        connection = HTTPConnection(host, port, timeout=1)
        try:
            connection.request("POST", ENDPOINT, body=BODY, headers=HEADERS)
            response = connection.getresponse()
            expected_origin = HEADERS["Origin"] if mode in ("cors", "all") else None
            if (response.status, response.version, response.read(), response.getheader("Content-Type"),
                    response.getheader("Access-Control-Allow-Origin")) != (
                        200, 11, BODY, "application/json", expected_origin):
                raise RuntimeError(f"Unexpected benchmark response for {mode}")
            return
        except ConnectionRefusedError:
            time.sleep(0.01)
        finally:
            connection.close()
    raise RuntimeError(f"Server failed to start for {mode}")


def trial(args, command, rate, repeat, mode, remote):
    prefix = args.output / f"{rate}-{repeat}-{mode}"
    with socket.socket() as reservation:
        reservation.bind((str(args.bind), 0))
        port = reservation.getsockname()[1]
    url = f"http://{args.bind}:{port}{ENDPOINT}"
    environment = {**os.environ, "KTSERVE_WORKERS": "1", "KTSERVE_ADAPTIVE_WORKERS": "0",
                   "KTSERVE_ADAPTIVE_HANDOFF": "0", "KTSERVE_EPOLL_TCP_NODELAY": "on"}
    log = prefix.with_suffix(".stderr.log")
    with prefix.with_suffix(".server.log").open("wb") as stdout, log.open("wb") as stderr:
        started = time.monotonic()
        server = subprocess.Popen(["taskset", "-c", args.server_cpus, *command, "--benchmark",
                                   str(args.bind), str(port), mode], env=environment, stdout=stdout, stderr=stderr)
        try:
            ready(server, str(args.bind), port, mode)
            startup_ms = (time.monotonic() - started) * 1000
            _, _, ready_rss, _ = fixed.metrics(server.pid)
            warmup = fixed.attack(args, rate, args.warmup, url, prefix.with_name(prefix.name + "-warmup"), remote,
                                  request=REQUEST)
            if mode in ("access-log", "all"):
                fixed.wait_for_logs(log)
            start, _, _, _ = fixed.metrics(server.pid)
            load = fixed.attack(args, rate, args.duration, url, prefix, remote, request=REQUEST)
            if mode in ("access-log", "all"):
                fixed.wait_for_logs(log)
            end, peak, rss, _ = fixed.metrics(server.pid)
        finally:
            server.terminate()
            try:
                server.wait(timeout=12)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
                raise RuntimeError(f"Server did not shut down promptly: {prefix}")
    if server.returncode not in (0, -15, 143):
        raise RuntimeError(f"Server exited with {server.returncode}: {prefix}")
    warm, _ = fixed.report(args, warmup, rate * args.warmup)
    measured, timing = fixed.report(args, load, rate * args.duration)
    cpu = end - start
    requests = measured["ok"]
    log_size = log.stat().st_size
    logs_expected = logs_written = drops_reported = None
    if mode in ("access-log", "all"):
        logs_expected = 1 + warm["ok"] + requests  # The readiness request also writes an access record.
        logs_written, _, drops_reported = fixed.count_records(log, 0, log_size, completed_record=COMPLETED)
    logging_valid = logs_written == logs_expected and not drops_reported
    return dict(mode=mode, rate=rate, repeat=repeat,
                valid=warm["valid"] and measured["valid"] and cpu >= 0 and logging_valid,
                attempted=measured["attempted"], completed=requests, completed_rps=requests / args.duration,
                startup_to_response_ms=round(startup_ms, 3), ready_rss_kib=ready_rss, peak_rss_kib=peak,
                end_rss_kib=rss, cpu_seconds=round(cpu, 6),
                cpu_seconds_per_million=round(cpu * 1_000_000 / requests, 3) if requests else None,
                stderr_bytes=log_size, logs_expected=logs_expected, logs_written=logs_written, drops_reported=drops_reported,
                **{f"p{p}_us": measured["latencies"][f"{p}th"] / 1000 for p in (50, 95, 99)}, **timing)


def summarize(rows, rates, modes):
    for rate in rates:
        baseline = {row["repeat"]: row for row in rows if row["rate"] == rate and row["mode"] == "none" and row["valid"]}
        for mode in modes:
            valid = [row for row in rows if row["rate"] == rate and row["mode"] == mode and row["valid"]]
            paired = [row for row in valid if row["repeat"] in baseline]
            values = {field: [row[field] for row in valid] for field in MEASUREMENTS}
            yield dict(rate=rate, mode=mode, valid_runs=len(valid), paired_runs=len(paired),
                       metrics={field: dict(median=median(samples), min=min(samples), max=max(samples))
                                for field, samples in values.items() if samples},
                       paired_delta={field: median(row[field] - baseline[row["repeat"]][field] for row in paired)
                                     for field in MEASUREMENTS} if paired else {})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", choices=("native", "jvm"))
    parser.add_argument("artifact", type=lambda path: Path(path).resolve())
    parser.add_argument("output", type=lambda path: Path(path).resolve())
    parser.add_argument("--toolchain", type=Path, required=True, help="Text file with build provenance")
    parser.add_argument("--bind", type=IPv4Address, default=IPv4Address("127.0.0.1"))
    parser.add_argument("--server-cpus", required=True)
    parser.add_argument("--load-cpus", required=True)
    parser.add_argument("--local", action="store_true", help="Run a same-host diagnostic using disjoint CPU sets")
    parser.add_argument("--vegeta", default="vegeta")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rates", type=int, nargs="+", default=[4000, 12000, 24000])
    parser.add_argument("--modes", nargs="+", choices=(*MODES, *JVM_MODES))
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--connections", type=int, default=16)
    parser.add_argument("--warmup", type=int, default=15)
    parser.add_argument("--duration", type=int, default=20)
    args = parser.parse_args()
    args.load_ssh = os.environ.get("LOAD_SSH")
    modes = args.modes or [*MODES, *(JVM_MODES if args.runtime == "jvm" else ())]
    if "none" not in modes or len(set(modes)) != len(modes) or (args.runtime == "native" and set(modes) & set(JVM_MODES)):
        parser.error("Use distinct modes including none. OpenTelemetry modes require the JVM")
    if sys.platform != "linux" or min(*args.rates, args.connections, args.repeats, args.warmup, args.duration) < 1:
        parser.error("Run on Linux with positive rates, connection counts and durations")
    if len(set(args.rates)) != len(args.rates) or args.bind.is_unspecified:
        parser.error("Use distinct rates and a specific IPv4 bind address")
    if args.local == bool(args.load_ssh) or (args.load_ssh and args.bind.is_loopback):
        parser.error("Set LOAD_SSH and a reachable --bind for a separate load host, or use --local")
    if args.load_ssh:
        load_id = fixed.output(*fixed.load_command(args, "cat", "/etc/machine-id"))
        if not load_id or load_id == fixed.output("cat", "/etc/machine-id"):
            parser.error("LOAD_SSH must identify a different Linux machine")
    server_cpus = fixed.affinity(args, args.server_cpus)
    load_cpus = fixed.affinity(args, args.load_cpus, load=True)
    if args.local and server_cpus & load_cpus:
        parser.error("Local server and client CPU sets must be disjoint")
    args.load_threads = min(2, len(load_cpus))

    if args.runtime == "native":
        artifacts = [args.artifact] if os.access(args.artifact, os.X_OK) and args.artifact.is_file() else []
        command = [str(args.artifact)]
    else:
        artifacts = sorted(args.artifact.glob("*.jar"))
        command = [args.java, "-cp", str(args.artifact / "*"), "codes.draeger.examples.extensions.MainKt"]
    if not artifacts:
        parser.error("Provide a Native release executable or a JVM distribution containing JARs")
    args.output.mkdir(parents=True)
    revision = os.environ.get("KTSERVE_SOURCE_REVISION") or fixed.output("git", "-C", SCRIPTS.parent, "rev-parse", "HEAD")
    source_status = os.environ.get("KTSERVE_SOURCE_STATUS")
    if source_status is None:
        source_status = fixed.output("git", "-C", SCRIPTS.parent, "status", "--short")
    metadata = dict(settings=vars(args), mode="local diagnostic" if args.local else "separate Linux load host",
                    git_revision=revision, git_status=source_status, toolchain=args.toolchain.read_text(), modes=modes,
                    server=platform.uname()._asdict(), server_cpu=fixed.output("lscpu"),
                    load=fixed.output(*fixed.load_command(args, "uname", "-srm")),
                    load_cpu=fixed.output(*fixed.load_command(args, "lscpu")),
                    load_tool=fixed.output(*fixed.load_command(args, args.vegeta, "-version")),
                    log_filesystem=fixed.output("stat", "-f", "-c", "%T", args.output), command=command,
                    artifact_hashes={artifact.name: hashlib.sha256(artifact.read_bytes()).hexdigest() for artifact in artifacts},
                    workload={**REQUEST, "endpoint": ENDPOINT, "http": "1.1"},
                    traces={"samplers": {"otel-unsampled": 0, "otel-sampled": 0.1},
                            "exporter": "bounded batch processor (2048) with an in-process discard sink"})
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2, default=str) + "\n")
    remote = None
    if args.load_ssh:
        remote = fixed.output(*fixed.load_command(args, "mktemp", "-d", "/tmp/ktserve-extension-rate.XXXXXXXX"))
        if not re.fullmatch(r"/tmp/ktserve-extension-rate\.[a-zA-Z0-9]+", remote):
            raise RuntimeError("Unexpected remote temporary directory")

    rows = []
    try:
        with (args.output / "runs.csv").open("w", newline="") as stream:
            for repeat in range(1, args.repeats + 1):
                for index, rate in enumerate(args.rates):
                    offset = (repeat - 1 + index) % len(modes)
                    for mode in modes[offset:] + modes[:offset]:
                        row = trial(args, command, rate, repeat, mode, remote)
                        writer = csv.DictWriter(stream, fieldnames=row)
                        if not rows:
                            writer.writeheader()
                        rows.append(row)
                        writer.writerow(row)
                        stream.flush()
                        print(f"{rate} req/s · {mode} · run {repeat} · valid={row['valid']}", flush=True)
    finally:
        if remote:
            subprocess.run(fixed.load_command(args, "rmdir", "--", remote), check=False, timeout=15)
    summary = list(summarize(rows, args.rates, modes))
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return 0 if all(row["valid"] for row in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
