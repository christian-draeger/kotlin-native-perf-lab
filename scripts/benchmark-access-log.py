#!/usr/bin/env python3
"""Compare ktServe access-log variants at identical offered HTTP/1.1 rates."""

import argparse
import csv
from dataclasses import dataclass
from hashlib import file_digest
from http.client import HTTPConnection
from ipaddress import IPv4Address
import json
import os
from pathlib import Path
import platform
import re
import shlex
import socket
from statistics import median
import subprocess
import sys
import time

from parse_vegeta import read_report
from parse_wrk import read_load_timing


SCRIPTS = Path(__file__).resolve().parent
ENDPOINT = "/small.bin"
PAYLOAD = bytes(range(256))
TIME = ["/usr/bin/time", "-f", "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M"]
COMPLETED = re.compile(rb"event=exchange method=GET status=200 duration_ns=[0-9]+ outcome=COMPLETED\n")
DROPPED = re.compile(rb"event=access_log_dropped count=([0-9]+)\n")


@dataclass(frozen=True)
class Variant:
    label: str
    binary: Path
    access_log: bool


def output(*command):
    return subprocess.check_output(command, text=True, timeout=15).strip()


def load_command(args, *command):
    if args.load_ssh:
        return ["ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "--",
                args.load_ssh, shlex.join(command)]
    return list(command)


def metrics(pid):
    cpu, peak, rss = output(sys.executable, SCRIPTS / "proc_metrics.py", str(pid)).split()
    stat = Path(f"/proc/{pid}/task/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
    reactor = (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK")
    return float(cpu), int(peak), int(rss), reactor


def ready(server, host, port):
    deadline = time.monotonic() + 5
    while server.poll() is None and time.monotonic() < deadline:
        connection = HTTPConnection(host, port, timeout=0.5)
        try:
            connection.request("GET", ENDPOINT)
            response = connection.getresponse()
            if response.status != 200 or response.version != 11 or response.read() != PAYLOAD:
                raise RuntimeError("Unexpected HTTP/1.1 benchmark response")
            return
        except ConnectionRefusedError:
            time.sleep(0.05)
        finally:
            connection.close()
    raise RuntimeError("ktServe did not start on the selected bind address")


def wait_for_logs(path):
    """Return a file boundary only after the writer has been quiet for 100 ms."""
    deadline = time.monotonic() + 5
    size = path.stat().st_size
    quiet_since = time.monotonic()
    while time.monotonic() < deadline:
        time.sleep(0.02)
        now = time.monotonic()
        current = path.stat().st_size
        if current != size:
            size, quiet_since = current, now
        elif now - quiet_since >= 0.1:
            return size
    raise RuntimeError(f"Access-log output did not settle: {path}")


def count_records(path, start, end):
    completed = other = drops = 0
    with path.open("rb") as stream:
        stream.seek(start)
        while stream.tell() < end:
            line = stream.readline(end - stream.tell())
            if COMPLETED.fullmatch(line):
                completed += 1
            elif match := DROPPED.fullmatch(line):
                drops += int(match[1])
            else:
                other += 1
    return completed, other, drops


def attack(args, rate, seconds, url, prefix, remote):
    raw = prefix.with_suffix(".vegeta.bin")
    timing = prefix.with_suffix(".client.txt")
    target = f"{remote}/{raw.name}" if remote else str(raw)
    command = ["taskset", "-c", args.load_cpus, "env", "LC_ALL=C", "NO_PROXY=*", f"GOMAXPROCS={args.load_threads}", *TIME,
               args.vegeta, "attack", f"-rate={rate}/s", f"-duration={seconds}s",
               f"-workers={args.connections}", f"-max-workers={args.connections}",
               f"-connections={args.connections}", f"-max-connections={args.connections}",
               "-keepalive=true", "-timeout=2s", "-http2=false", "-redirects=-1", "-max-body=0",
               "-output", target]
    with timing.open("wb") as stderr:
        subprocess.run(load_command(args, *command), input=f"GET {url}\n".encode(),
                       stderr=stderr, check=True, timeout=seconds + 20)
    return raw, timing, target


def report(args, sample, planned):
    raw, timing, target = sample
    json_path = raw.with_suffix(".json")
    with json_path.open("wb") as stream:
        subprocess.run(load_command(args, args.vegeta, "report", "-type=json", target),
                       stdout=stream, check=True, timeout=60)
    if args.load_ssh:
        with raw.open("wb") as stream:
            subprocess.run(load_command(args, "cat", target), stdout=stream, check=True, timeout=120)
        subprocess.run(load_command(args, "rm", "-f", "--", target), check=True, timeout=15)
    result = read_report(json_path, planned)
    timing = read_load_timing(timing)
    result["valid"] &= 0 < (timing["load_cpu_equivalents"] or 0) <= 0.85 * args.load_threads
    subprocess.run(["gzip", "-n", raw], check=True, timeout=120)
    return result, timing


def trial(args, site, rate, number, variant, remote):
    enabled = variant.access_log
    prefix = args.output / f"{rate}-{number}-{variant.label}"
    sink = args.log_dir / f"{prefix.name}.access.log"
    command = ["taskset", "-c", args.server_cpus, str(variant.binary), "serve", str(site)]
    if enabled:
        command.append("--access-log")
    with socket.socket() as reservation:
        reservation.bind((str(args.bind), 0))
        port = reservation.getsockname()[1]
    url = f"http://{args.bind}:{port}{ENDPOINT}"
    env = {**os.environ, "BIND_HOST": str(args.bind), "PORT": str(port), "KTSERVE_BACKEND": "epoll", "KTSERVE_WORKERS": "1",
           "KTSERVE_EPOLL_TCP_NODELAY": "on", "KTSERVE_ADAPTIVE_WORKERS": "0", "KTSERVE_ADAPTIVE_HANDOFF": "0"}
    with sink.open("xb") as stderr, prefix.with_suffix(".server.log").open("wb") as stdout:
        server = subprocess.Popen(command, env=env, stdout=stdout, stderr=stderr)
        try:
            ready(server, str(args.bind), port)
            warmup = attack(args, rate, args.warmup, url, prefix.with_name(prefix.name + "-warmup"), remote)
            warm_end = wait_for_logs(sink)
            start, _, _, reactor_start = metrics(server.pid)
            measured = attack(args, rate, args.duration, url, prefix, remote)
            measure_end = wait_for_logs(sink)
            end, peak, rss, reactor_end = metrics(server.pid)
        finally:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
                raise RuntimeError(f"ktServe could not stop promptly: {prefix}")
    if server.returncode not in (0, -15):
        raise RuntimeError(f"ktServe exited with {server.returncode}: {prefix}")

    warm, _ = report(args, warmup, rate * args.warmup)
    load, timing = report(args, measured, rate * args.duration)
    spool_size = sink.stat().st_size
    timed, timed_other, timed_dropped = count_records(sink, warm_end, measure_end)
    late, late_other, late_dropped = count_records(sink, measure_end, spool_size)
    warm_records, warm_other, warm_dropped = count_records(sink, 0, warm_end)
    expected_log_total = warm["ok"] + load["ok"] + 1  # Includes readiness probe.
    actual_log_total = warm_records + timed + late
    drops = warm_dropped + timed_dropped + late_dropped
    total_coverage = 100 * actual_log_total / expected_log_total if enabled else None
    log_clean = timed_other + warm_other + late_other == 0 and (
        actual_log_total == expected_log_total and timed + late == load["ok"] and drops == 0 if enabled
        else spool_size == 0
    )
    cpu = end - start
    reactor_cpu = reactor_end - reactor_start
    valid = warm["valid"] and load["valid"] and cpu >= 0 and reactor_cpu >= 0
    return dict(rate=rate, run=number, variant=variant.label, access_log="on" if enabled else "off",
                load_valid=valid, log_complete=log_clean,
                offered=load["planned"], attempted=load["attempted"], completed=load["ok"],
                failed=load["attempted"] - load["ok"], completed_rps=round(load["ok"] / args.duration, 3),
                p50_us=load["latencies"]["50th"] / 1000, p95_us=load["latencies"]["95th"] / 1000,
                p99_us=load["latencies"]["99th"] / 1000, cpu_seconds=round(cpu, 6),
                cpu_seconds_per_million=round(cpu * 1_000_000 / load["ok"], 3) if load["ok"] else None,
                reactor_cpu_seconds=round(reactor_cpu, 6),
                reactor_cpu_seconds_per_million=round(reactor_cpu * 1_000_000 / load["ok"], 3) if load["ok"] else None,
                peak_rss_kib=peak, end_rss_kib=rss, log_timed=timed, log_late=late, log_total=actual_log_total,
                log_total_expected=expected_log_total if enabled else 0, log_total_coverage_pct=total_coverage,
                log_other=timed_other + warm_other + late_other, log_reported_drops=drops if server.returncode == 0 else None,
                shutdown_exit_code=server.returncode, log_bytes=spool_size, **timing)


def affinity(args, mask, load=False):
    command = ["taskset", "-c", mask, "python3", "-c", "import os; print(*sorted(os.sched_getaffinity(0)))"]
    return {int(cpu) for cpu in output(*(load_command(args, *command) if load else command)).split()}


def summarize(args, variants, rows):
    fields = ("completed_rps", "p50_us", "p95_us", "p99_us", "cpu_seconds_per_million",
              "reactor_cpu_seconds_per_million", "peak_rss_kib",
              "log_total_coverage_pct", "log_reported_drops")
    summaries = []
    for rate in args.rates:
        for variant in variants:
            valid = [row for row in rows if row["rate"] == rate and row["variant"] == variant.label and row["load_valid"]]
            values = {field: [row[field] for row in valid if row[field] is not None] for field in fields}
            summaries.append(dict(rate=rate, variant=variant.label, access_log="on" if variant.access_log else "off",
                                  valid_runs=len(valid),
                                  **{field: median(samples) if samples else None for field, samples in values.items()}))
    (args.output / "medians.json").write_text(json.dumps(summaries, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=lambda path: Path(path).resolve())
    parser.add_argument("output", type=lambda path: Path(path).resolve())
    parser.add_argument("--toolchain", type=Path, required=True, help="Build provenance of the supplied release binary")
    parser.add_argument("--compare-binary", type=lambda path: Path(path).resolve(),
                        help="Compare the original and extension binaries with logging active on both")
    parser.add_argument("--compare-toolchain", type=Path,
                        help="Build provenance of the extension binary when recorded in a separate file")
    parser.add_argument("--local", action="store_true", help="Use a same-host diagnostic when LOAD_SSH is unset")
    parser.add_argument("--bind", type=IPv4Address, default=IPv4Address("127.0.0.1"))
    parser.add_argument("--server-cpus", required=True)
    parser.add_argument("--load-cpus", required=True)
    parser.add_argument("--log-dir", type=lambda path: Path(path).resolve(), help="Existing disk-backed log directory")
    parser.add_argument("--vegeta", default="vegeta", help="Vegeta executable on the load host")
    parser.add_argument("--rates", type=int, nargs="+", default=(4000, 12000, 24000))
    parser.add_argument("--connections", type=int, default=16)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--duration", type=int, default=15)
    args = parser.parse_args()
    if args.compare_toolchain and not args.compare_binary:
        parser.error("--compare-toolchain requires --compare-binary")
    if args.compare_binary:
        variants = (Variant("legacy-on", args.binary, True), Variant("extension-on", args.compare_binary, True))
    else:
        variants = (Variant("off", args.binary, False), Variant("on", args.binary, True))
    args.load_ssh = os.environ.get("LOAD_SSH")
    if args.local == bool(args.load_ssh):
        parser.error("Set LOAD_SSH for a separate Linux load host or use --local")
    if sys.platform != "linux" or min(*args.rates, args.connections, args.repeats, args.warmup, args.duration) < 1:
        parser.error("Run on Linux with positive rates, connection count, repeats and durations")
    if len(set(args.rates)) != len(args.rates):
        parser.error("Supply distinct offered rates")
    if args.bind.is_unspecified or (args.load_ssh and args.bind.is_loopback):
        parser.error("Use a specific IPv4 interface, reachable from the load host for LOAD_SSH")
    if args.load_ssh:
        load_machine = output(*load_command(args, "cat", "/etc/machine-id"))
        server_machine = output("cat", "/etc/machine-id")
        if not load_machine or not server_machine or load_machine == server_machine:
            parser.error("LOAD_SSH must refer to a different Linux machine")
    server_cpus = affinity(args, args.server_cpus)
    load_cpus = affinity(args, args.load_cpus, load=True)
    if args.local and server_cpus & load_cpus:
        parser.error("Use disjoint local server and load CPU sets")
    args.load_threads = min(2, len(load_cpus))
    args.output.mkdir(parents=True)
    log_root = args.log_dir or args.output
    fs = output("stat", "-f", "-c", "%T", log_root)
    if fs in ("tmpfs", "ramfs"):
        parser.error("Use a disk-backed log directory")
    args.log_dir = log_root / ("access-logs" if log_root == args.output else f"{args.output.name}-access-logs")
    args.log_dir.mkdir()
    site = args.output / "site"
    site.mkdir()
    (site / ENDPOINT[1:]).write_bytes(PAYLOAD)
    with args.binary.open("rb") as stream:
        digest = file_digest(stream, "sha256").hexdigest()
    toolchain = args.toolchain.read_text()
    first_binary = "legacy" if args.compare_binary else "primary"
    binaries = {first_binary: dict(path=args.binary, sha256=digest, toolchain=toolchain)}
    if args.compare_binary:
        with args.compare_binary.open("rb") as stream:
            compare_digest = file_digest(stream, "sha256").hexdigest()
        compare_toolchain = args.compare_toolchain.read_text() if args.compare_toolchain else toolchain
        binaries["extension"] = dict(path=args.compare_binary, sha256=compare_digest, toolchain=compare_toolchain)
    revision = os.environ.get("KTSERVE_SOURCE_REVISION") or output("git", "-C", SCRIPTS.parent, "rev-parse", "HEAD")
    source_status = os.environ.get("KTSERVE_SOURCE_STATUS")
    if source_status is None:
        source_status = output("git", "-C", SCRIPTS.parent, "status", "--short")
    metadata = dict(settings=vars(args), mode="local diagnostic" if args.local else "separate Linux load host",
                    binary_sha256=digest, source_revision=revision, source_status=source_status,
                    binaries=binaries,
                    virtualization_host_cpu=os.environ.get("KTSERVE_HOST_CPU"),
                    server_platform=platform.uname()._asdict(), server_cpu=output("lscpu"),
                    load_platform=output(*load_command(args, "uname", "-srm")),
                    load_cpu=output(*load_command(args, "lscpu")),
                    load_tool=output(*load_command(args, args.vegeta, "-version")),
                    toolchain=toolchain, sink_filesystem=fs, endpoint=ENDPOINT, body_bytes=len(PAYLOAD))
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2, default=str) + "\n")
    remote = output(*load_command(args, "mktemp", "-d", "/tmp/ktserve-log-rate.XXXXXXXX")) if args.load_ssh else None
    if remote and not re.fullmatch(r"/tmp/ktserve-log-rate\.[a-zA-Z0-9]+", remote):
        raise RuntimeError("Unexpected remote temporary directory")
    rows = []
    try:
        with (args.output / "runs.csv").open("w", newline="") as stream:
            writer = None
            for number in range(1, args.repeats + 1):
                for rate in args.rates:
                    for variant in variants if number % 2 else reversed(variants):
                        row = trial(args, site, rate, number, variant, remote)
                        rows.append(row)
                        if writer is None:
                            writer = csv.DictWriter(stream, fieldnames=row)
                            writer.writeheader()
                        writer.writerow(row)
                        stream.flush()
                        print(f"{rate} RPS, {row['variant']} {number} · {row['completed_rps']} completed/s, "
                              f"p99 {row['p99_us']:.1f} µs, valid load {row['load_valid']}, "
                              f"complete logs {row['log_complete']}", flush=True)
    finally:
        if remote:
            subprocess.run(load_command(args, "rmdir", "--", remote), check=False, timeout=15)
    summarize(args, variants, rows)
    return 0 if all(row["load_valid"] and row["log_complete"] for row in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
