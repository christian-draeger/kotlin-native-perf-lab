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
from contextlib import contextmanager, suppress
from pathlib import Path

from parse_wrk import read_load_timing

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


def request(port, path, upload_size=UPLOAD_SIZE):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        if path == "/upload":
            body = UPLOAD if upload_size == UPLOAD_SIZE else b"x" * upload_size
            connection.request("POST", path, body, {"Content-Type": "application/octet-stream"})
        else:
            connection.request("GET", path)
        response = connection.getresponse()
        body = response.read()
        if response.status != 200 or response.version != 11:
            raise ValueError(f"{path}: expected HTTP/1.1 200, got {response.version} {response.status}")
        return response.headers, body
    finally:
        connection.close()


def require_response(port, path, content_type, expected, upload_size=UPLOAD_SIZE):
    headers, body = request(port, path, upload_size)
    observed_type = headers.get("Content-Type", "").split(";", 1)[0]
    observed = json.loads(body) if content_type == "application/json" else body
    if observed_type != content_type or observed != expected:
        raise ValueError(f"{path}: response parity failed (type={observed_type!r}, length={len(body)})")
    if path == "/download" and headers.get("Transfer-Encoding", "").lower() != "chunked":
        raise ValueError(f"{path}: expected HTTP/1.1 chunked streaming")


def check_responses(port, mixed):
    require_response(port, "/health", "text/plain", b"ok")
    require_response(port, "/json", "application/json", USER)
    require_response(port, "/upload", "text/plain", str(UPLOAD_SIZE).encode())
    require_response(port, "/download", "application/octet-stream", DOWNLOAD)
    if mixed:
        for size in (1024, 64 * 1024):
            require_response(port, "/upload", "text/plain", str(size).encode(), size)
        for catalog_id in (123, 987):
            items = [
                {"sku": f"{catalog_id}-{index}", "name": f"Café item {index}",
                 "description": "Roasted in München and packed for delivery.",
                 "stock": {"available": index > 0, "units": index * 3}}
                for index in range(32)
            ]
            require_response(port, f"/catalog/{catalog_id}", "application/json", {"id": catalog_id, "items": items})


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


def stop_server(process, port, family):
    if process.poll() is not None:
        return
    if family == "ktor-native":
        try:
            request(port, "/stop")
            process.wait(timeout=6)
            return
        except (OSError, ValueError, http.client.HTTPException, subprocess.TimeoutExpired):
            pass
    with suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        process.wait()


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
            check_responses(port, args.load_tool == "k6")
            yield process, port
        finally:
            stop_server(process, port, args.family)


def run_output(*command):
    return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True).strip()


def cpu_prefix(cpus):
    return ["taskset", "-c", ",".join(str(cpu) for cpu in cpus)]


def write_environment(args, git_sha, server_cpus, load_cpus):
    output = args.output / "environment.txt"
    versions = [run_output("java", "-version")] if args.family != "ktor-native" else []
    if args.load_tool == "k6":
        load_version = run_output("k6", "version")
        workload = [
            "workload_model=constant HTTP arrival rate, one request per iteration",
            "workloads=json:40%,catalog:25%,upload-1k:10%,upload-24k:5%,upload-64k:10%,download:10%",
            "upload_sizes_bytes=1024,24576,65536",
            f"offered_rates={','.join(map(str, args.rates))}",
            f"max_vus_per_scenario={args.vus}",
        ]
    else:
        load_version = subprocess.run(
            ["wrk", "--version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        ).stdout.splitlines()[0]
        workload = [f"workloads={','.join(WORKLOADS)}", f"upload_size_bytes={UPLOAD_SIZE}",
                    f"connections={args.connections}", f"load_threads={args.threads}"]
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
            f"git_worktree={'dirty' if run_output('git', '-C', SCRIPTS.parent, 'status', '--porcelain') else 'clean'}",
            f"integration_family={args.family}",
            "benchmark_suite=integration",
            f"load_generator=local {args.load_tool} on loopback with disjoint CPU affinity",
            f"load_tool={args.load_tool}",
            f"load_tool_version={load_version}",
            f"server_cpus={','.join(map(str, server_cpus))}",
            f"load_cpus={','.join(map(str, load_cpus))}",
            "request_protocol=HTTP/1.1",
            *workload,
            f"download_size_bytes={len(DOWNLOAD)}",
            "download_inter_chunk_delay_ms=50",
            f"repetitions={args.repeats}",
            f"warmup_seconds={args.warmup}",
            f"target_duration_seconds={args.duration}",
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


def read_k6_summary(path, offered, duration):
    summary = json.loads(path.read_text())
    metrics = summary["metrics"]

    def count(name):
        return int(metrics.get(name, {}).get("values", {}).get("count", 0))

    requests = count("http_reqs")
    failed = count("perflab_failed_responses")
    dropped = count("dropped_iterations")
    latency = metrics.get("perflab_success_duration", {}).get("values", {})
    successful = int(latency.get("count", 0))
    operations = {}
    rates = summary["perflab_rates"]
    operation_counts_match = True
    for name, rate in rates.items():
        stats = metrics.get(f"perflab_{name}_duration", {}).get("values", {})
        completed = int(stats.get("count", 0))
        expected = rate * duration
        if not completed or abs(completed - expected) > max(1, expected / 100):
            operation_counts_match = False
        if completed:
            operations[name] = {"rps": round(completed / duration, 3), "p99": stats["p(99)"]}
    valid = (
        successful > 0 and requests == successful and failed == dropped == 0
        and count("iterations") == requests and abs(requests - offered) <= max(len(rates), offered / 100)
        and bool(rates) and sum(rates.values()) * duration == offered and operation_counts_match
    )
    return {
        "valid": int(valid), "requests": requests, "successful_responses": successful,
        "failed_responses": failed, "dropped_iterations": dropped, "offered_requests": offered,
        "rps": round(successful / duration, 3),
        "operations": json.dumps(operations, separators=(",", ":")),
        **{f"p{quantile}_us": round(latency[f"p({quantile})"] * 1000, 3) if successful else ""
           for quantile in (50, 95, 99)},
    }


def load_k6(args, affinity, port, rate, duration, prefix):
    summary = prefix.with_suffix(".k6.json")
    log = prefix.with_suffix(".k6.log")
    env = {key: value for key, value in os.environ.items() if not key.startswith("K6_")}
    env.update({
        "GOMAXPROCS": str(args.threads), "PERFLAB_TARGET": f"http://127.0.0.1:{port}",
        "PERFLAB_RATE": str(rate), "PERFLAB_DURATION": str(duration),
        "PERFLAB_VUS": str(args.vus), "PERFLAB_SUMMARY": str(summary),
    })
    with log.open("w") as output:
        subprocess.run([
            *affinity, "/usr/bin/time", "-f",
            "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M",
            "k6", "run", "--no-usage-report", "--quiet", "--no-color", str(SCRIPTS / "k6-integration.js"),
        ], env=env, stdout=output, stderr=subprocess.STDOUT, timeout=duration + 20, check=True)
    return {**read_k6_summary(summary, rate * duration, duration), **read_load_timing(log)}


def trial_k6(args, git_sha, engine, rate, run, server_affinity, load_affinity):
    variant = f"{args.family}-{engine}"
    stem = args.output / f"{variant}-mixed-{rate}-{run}"
    metrics = (sys.executable, SCRIPTS / "proc_metrics.py")
    with server(args, engine, server_affinity, stem.with_suffix(".server.log")) as (process, port):
        warmup = load_k6(args, load_affinity, port, rate, args.warmup, stem.with_name(stem.name + "-warmup"))
        before, _, _ = run_output(*metrics, str(process.pid)).split()
        row = load_k6(args, load_affinity, port, rate, args.duration, stem)
        after, peak, rss = run_output(*metrics, str(process.pid)).split()
    cpu = float(after) - float(before)
    valid = row["valid"] and warmup["valid"] and cpu >= 0
    row.update({
        "variant": variant, "endpoint": f"Mixed · {rate} req/s", "run": run, "valid": int(valid),
        "connections": "", "threads": "", "offered_rate": rate, "vus_per_scenario": args.vus,
        "warmup_s": args.warmup, "target_duration_s": args.duration,
        "cpu_seconds": round(cpu, 6),
        "cpu_seconds_per_million_requests": round(cpu / row["successful_responses"] * 1_000_000, 3) if valid else "",
        "peak_rss_kib": peak, "end_rss_kib": rss, "git_sha": git_sha,
    })
    path = args.output / "results.csv"
    existed = path.exists()
    with path.open("a", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=row)
        if not existed:
            writer.writeheader()
        writer.writerow(row)
    print(f"{variant} {rate} offered req/s trial {run}: {row['rps']:.1f} successful req/s; "
          f"{row['failed_responses']} failed, {row['dropped_iterations']} dropped, valid={bool(valid)}", flush=True)


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
    if args.load_tool == "wrk":
        (args.output / "wrk-upload.lua").write_text(
            'wrk.method = "POST"\n'
            f'wrk.body = string.rep("x", {UPLOAD_SIZE})\n'
            'wrk.headers["Content-Type"] = "application/octet-stream"\n\n' +
            (SCRIPTS / "wrk-stats.lua").read_text()
        )
    workloads = WORKLOADS if args.load_tool == "wrk" else args.rates
    measure = trial if args.load_tool == "wrk" else trial_k6

    engines = list(ENGINES[args.family])
    for run in range(1, args.repeats + 1):
        for index, workload in enumerate(workloads):
            offset = (run - 1 + index) % len(engines)
            for engine in engines[offset:] + engines[:offset]:
                measure(args, git_sha, engine, workload, run, server_affinity, load_affinity)

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
    parser.add_argument("--load-tool", choices=("k6", "wrk"), default="k6")
    parser.add_argument("--rates", nargs="+", type=int, default=[200, 800], help="Mixed requests per second for k6")
    parser.add_argument("--vus", type=int, default=16, help="k6 virtual users per scenario")
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
    if args.vus < 1 or any(rate < 20 or rate % 20 for rate in args.rates) or len(set(args.rates)) != len(args.rates):
        parser.error("Expected a positive VU count and distinct k6 arrival rates in multiples of 20")
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
