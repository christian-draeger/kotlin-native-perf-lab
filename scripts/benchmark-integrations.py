#!/usr/bin/env python3
"""Compare transport engines within an otherwise identical integration application."""

import argparse
import csv
import hashlib
import http.client
import json
import os
import platform
import signal
import socket
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
ENGINES = {
    "ktor-native": {"cio": "cio", "ktserve": "nativeserve"},
    "ktor-jvm": {"cio": "cio", "netty": "netty", "ktserve": "nativeserve"},
    "spring-webflux": {"reactor-netty": "reactor-netty", "ktserve": "nativeserve"},
}
UPLOAD_SIZE = 24 * 1024
UPLOAD = b"x" * UPLOAD_SIZE
DOWNLOAD = b"s" * (16 * 1024) + b"t" * (16 * 1024 + 3)
USER = {"id": 42, "name": "benchmark-user", "active": True}
WORKLOADS = ("/json", "/upload", "/download")


def request(port, path):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        if path == "/upload":
            connection.request("POST", path, UPLOAD, {"Content-Type": "application/octet-stream"})
        else:
            connection.request("GET", path)
        response = connection.getresponse()
        body = response.read()
        if response.status != 200 or response.version != 11:
            raise ValueError(f"{path}: expected HTTP/1.1 200, got {response.version} {response.status}")
        return response.headers, body
    finally:
        connection.close()


def require_response(port, path, content_type, expected):
    headers, body = request(port, path)
    observed_type = headers.get("Content-Type", "").split(";", 1)[0]
    observed = json.loads(body) if content_type == "application/json" else body
    if observed_type != content_type or observed != expected:
        raise ValueError(f"{path}: response parity failed (type={observed_type!r}, length={len(body)})")
    if path == "/download" and headers.get("Transfer-Encoding", "").lower() != "chunked":
        raise ValueError(f"{path}: expected HTTP/1.1 chunked streaming")


def check_responses(port):
    require_response(port, "/health", "text/plain", b"ok")
    require_response(port, "/json", "application/json", USER)
    require_response(port, "/upload", "text/plain", str(UPLOAD_SIZE).encode())
    require_response(port, "/download", "application/octet-stream", DOWNLOAD)


def wait_until_ready(process, port, log, expected_engine):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and process.poll() is None:
        try:
            if request(port, "/health")[1] == b"ok" and (
                expected_engine is None or f"perflab engine={expected_engine}\n" in log.read_text()
            ):
                return
        except (OSError, ValueError, http.client.HTTPException):
            pass
        time.sleep(0.1)
    raise RuntimeError(f"Server did not become healthy. See {log}")


@contextmanager
def server(args, engine, affinity, log):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    mode = ENGINES[args.family][engine]
    with log.open("w") as output:
        process = subprocess.Popen(
            [*affinity, str(args.executable), str(port), mode],
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            expected_engine = mode if args.family == "spring-webflux" else None
            wait_until_ready(process, port, log, expected_engine)
            check_responses(port)
            yield process, port
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()


def run_output(*command):
    return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True).strip()


def cpu_prefix(cpus):
    return ["taskset", "-c", ",".join(str(cpu) for cpu in cpus)]


def write_environment(args, git_sha, server_cpus, load_cpus):
    output = args.output / "environment.txt"
    versions = [run_output("java", "-version")] if args.family != "ktor-native" else []
    wrk_version = subprocess.run(
        ["wrk", "--version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    ).stdout.splitlines()
    dependencies = sorted((args.executable.parent.parent / "lib").glob("*.jar"))
    with (args.output / "application.sha256").open("w") as manifest:
        for path in [args.executable, *dependencies]:
            with path.open("rb") as binary:
                digest = hashlib.file_digest(binary, "sha256").hexdigest()
            manifest.write(f"{digest}  {path.name}\n")
    with output.open("a") as metadata:
        metadata.write("\n".join([
            f"machine={platform.machine()}",
            f"kernel={platform.release()}",
            f"git_sha={git_sha}",
            f"integration_family={args.family}",
            "benchmark_suite=integration",
            "load_generator=local wrk on loopback with disjoint CPU affinity",
            f"server_cpus={','.join(map(str, server_cpus))}",
            f"load_cpus={','.join(map(str, load_cpus))}",
            "request_protocol=HTTP/1.1",
            f"workloads={','.join(WORKLOADS)}",
            f"upload_size_bytes={UPLOAD_SIZE}",
            f"download_size_bytes={len(DOWNLOAD)}",
            "download_inter_chunk_delay_ms=50",
            f"connections={args.connections}",
            f"load_threads={args.threads}",
            f"repetitions={args.repeats}",
            f"warmup_seconds={args.warmup}",
            f"target_duration_seconds={args.duration}",
            f"wrk_version={wrk_version[0]}",
            *(f"engine_{engine}={mode}" for engine, mode in ENGINES[args.family].items()),
            *versions,
            "",
        ]))
        metadata.write(run_output("lscpu") + "\n")


def load(args, affinity, port, endpoint, duration, log):
    lua = args.output / "wrk-upload.lua" if endpoint == "/upload" else SCRIPTS / "wrk-stats.lua"
    with log.open("w") as output:
        subprocess.run([
            *affinity, "/usr/bin/time", "-f",
            "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M",
            "wrk", "-t", str(args.threads), "-c", str(args.connections),
            "-d", f"{duration}s", "--timeout", "2s", "--latency", "-s", str(lua),
            f"http://127.0.0.1:{port}{endpoint}",
        ], stdout=output, stderr=subprocess.STDOUT, timeout=duration + 15, check=True)


def trial(args, git_sha, engine, endpoint, run, server_affinity, load_affinity):
    variant = f"{args.family}-{engine}"
    stem = args.output / f"{variant}-{endpoint[1:]}-{run}"
    warmup_log = stem.with_suffix(".warmup.log")
    measurement_log = stem.with_suffix(".wrk.log")
    with server(args, engine, server_affinity, stem.with_suffix(".server.log")) as (process, port):
        load(args, load_affinity, port, endpoint, args.warmup, warmup_log)
        before = run_output(sys.executable, SCRIPTS / "proc_metrics.py", str(process.pid)).split()[0]
        load(args, load_affinity, port, endpoint, args.duration, measurement_log)
        after, peak, rss = run_output(sys.executable, SCRIPTS / "proc_metrics.py", str(process.pid)).split()
        subprocess.run([
            sys.executable, str(SCRIPTS / "parse_wrk.py"),
            "--log", str(measurement_log), "--warmup-log", str(warmup_log),
            "--csv", str(args.output / "results.csv"), "--variant", variant,
            "--endpoint", endpoint, "--git-sha", git_sha, "--run", str(run),
            "--connections", str(args.connections), "--threads", str(args.threads),
            "--duration", str(args.duration), "--warmup", str(args.warmup),
            "--cpu-start", before, "--cpu-end", after, "--peak-rss-kib", peak, "--rss-kib", rss,
        ], check=True)


def benchmark(args):
    available_cpus = sorted(os.sched_getaffinity(0))
    if len(available_cpus) < 2 + args.threads:
        raise SystemExit("The benchmark requires two server CPUs and one separate CPU per load thread")
    server_cpus = available_cpus[:2]
    load_cpus = available_cpus[2:2 + args.threads]
    server_affinity = cpu_prefix(server_cpus)
    load_affinity = cpu_prefix(load_cpus)
    git_sha = run_output("git", "-C", SCRIPTS.parent, "rev-parse", "HEAD")
    write_environment(args, git_sha, server_cpus, load_cpus)
    (args.output / "wrk-upload.lua").write_text(
        'wrk.method = "POST"\n'
        f'wrk.body = string.rep("x", {UPLOAD_SIZE})\n'
        'wrk.headers["Content-Type"] = "application/octet-stream"\n\n' +
        (SCRIPTS / "wrk-stats.lua").read_text()
    )

    engines = list(ENGINES[args.family])
    for run in range(1, args.repeats + 1):
        for index, endpoint in enumerate(WORKLOADS):
            offset = (run - 1 + index) % len(engines)
            for engine in engines[offset:] + engines[:offset]:
                trial(args, git_sha, engine, endpoint, run, server_affinity, load_affinity)

    with (args.output / "results.csv").open() as results:
        invalid = [row for row in csv.DictReader(results) if row["valid"] != "1"]
    if invalid:
        raise SystemExit(f"{len(invalid)} integration trials failed. See raw logs in {args.output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--family", choices=ENGINES, required=True)
    parser.add_argument("--executable", type=lambda path: Path(path).resolve(), required=True)
    parser.add_argument("--output", type=lambda path: Path(path).resolve(), required=True)
    parser.add_argument("--smoke-only", action="store_true")
    parser.add_argument("--connections", type=int, default=16)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--duration", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=15)
    args = parser.parse_args()
    if not os.access(args.executable, os.X_OK):
        parser.error(f"Expected an executable application launcher at {args.executable}")
    if min(args.repeats, args.duration, args.warmup, args.threads) < 1 or args.connections < args.threads:
        parser.error("Expected positive durations, repeats and at least one connection per load thread")
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "results.csv").exists():
        parser.error("Output directory already contains results.csv")
    if args.smoke_only:
        for engine in ENGINES[args.family]:
            with server(args, engine, [], args.output / f"{args.family}-{engine}.server.log"):
                print(f"{args.family}-{engine} HTTP/1.1 response parity passed", flush=True)
    else:
        if platform.system() != "Linux":
            parser.error("Benchmarks require Linux process and CPU affinity metrics. Use --smoke-only for local checks.")
        benchmark(args)


if __name__ == "__main__":
    main()
