#!/usr/bin/env python3
"""Explore Linux worker scaling on one host with disjoint CPU affinity masks."""

import argparse
import csv
from datetime import datetime
from hashlib import file_digest
from http.client import HTTPConnection
import json
from math import ceil
import os
from pathlib import Path
import re
import socket
from statistics import median
import subprocess
from threading import Event, Thread
import time

from parse_vegeta import read_report
from parse_wrk import error_count, read_load_timing, read_stats


SCRIPTS = Path(__file__).resolve().parent
VARIANTS = {"fixed-1": 1, "fixed-2": 2, "adaptive": 1}
HANDOFF = "adaptive-handoff"
EAGER = "eager"
STANDBY = "standby"
LOAD_TIMING = "perflab_load elapsed_s=%e user_s=%U system_s=%S peak_rss_kib=%M"
ACTIVE_WORKERS = re.compile(r"^ktServe adaptive active_workers=([12])\b", re.MULTILINE)
ADAPTIVE_EVENTS = re.compile(
    r"^ktServe adaptive ((?:active_workers|retiring_worker|resumed_worker|handoff_sent|handoff_received|"
    r"standby_ready|parked_worker|worker_cleanup_ms)=\d+)"
    r"(?: elapsed_ms=(\d+))?$", re.MULTILINE,
)
CONNECTIONS = 64
SKEW_HOT_CONNECTIONS = 8
POST_SCALE_SECOND = 5
EPOLL_DATA = re.compile(r"^tfd:\s+(\d+)\s+events:\s+\S+\s+data:\s+([0-9a-f]+)", re.MULTILINE)


def available_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def health(server, port):
    deadline = time.monotonic() + 5
    while server.poll() is None and time.monotonic() < deadline:
        connection = HTTPConnection("127.0.0.1", port, timeout=0.2)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            if response.status == 200 and response.read() == b"ok":
                return
        except OSError:
            time.sleep(0.05)
        finally:
            connection.close()
    raise RuntimeError(f"ktServe did not serve a valid health response on port {port}")


def proc_sample(pid):
    numbers = subprocess.check_output(["python3", SCRIPTS / "proc_metrics.py", str(pid)], text=True).split()
    return float(numbers[0]), int(numbers[1]), int(numbers[2])


def stop(server):
    if server.poll() is None:
        server.terminate()
    try:
        return server.wait(timeout=8)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait()
        raise RuntimeError("ktServe did not shut down on SIGTERM")


def observe_epoll(pid, start, duration, stopped, observations):
    fd_root = Path(f"/proc/{pid}/fd")
    offsets = (0.1, 0.4) if duration <= 1 else (1, POST_SCALE_SECOND + 1)
    for offset in offsets:
        if offset >= duration or stopped.wait(max(0, start + offset - time.monotonic())):
            return
        sampled_at = round(time.monotonic() - start, 3)
        registrations = {}
        sockets = set()
        for fd in fd_root.iterdir():
            try:
                target = str(fd.readlink())
                if target == "anon_inode:[eventpoll]":
                    registrations[fd.name] = (fd_root.parent / "fdinfo" / fd.name).read_text()
                elif target.startswith("socket:["):
                    sockets.add(fd.name)
            except FileNotFoundError:
                continue
        epolls = {}
        for fd, info in registrations.items():
            # Socket token zero is the listener; eventfd and other control handles are never peers.
            peers = sum(peer_fd in sockets and int(token, 16) != 0 for peer_fd, token in EPOLL_DATA.findall(info))
            epolls[fd] = {"peers": peers, "fdinfo": info}
        observations.append({"elapsed_s": sampled_at, "epolls": epolls})


def vegeta_windows(raw, seconds):
    records = subprocess.check_output(["vegeta", "encode", "-to=json", raw], text=True)
    samples = [json.loads(record) for record in records.splitlines()]
    if not samples:
        return [], None
    arrivals = [datetime.fromisoformat(sample["timestamp"]) for sample in samples]
    start = min(arrivals)
    windows = [[] for _ in range(seconds)]
    for sample, arrival in zip(samples, arrivals):
        index = min(int((arrival - start).total_seconds()), seconds - 1)
        windows[index].append(sample["latency"] / 1000)
    output = []
    for index, latencies in enumerate(windows):
        if latencies:
            latencies.sort()
            output.append({
                "start_us": index * 1_000_000, "duration_us": 1_000_000, "requests": len(latencies),
                "p99_us": latencies[ceil(len(latencies) * 0.99) - 1],
                "p999_us": latencies[ceil(len(latencies) * 0.999) - 1], "max_us": latencies[-1],
            })
    all_latencies = sorted(latency for window in windows for latency in window)
    return output, all_latencies[ceil(len(all_latencies) * 0.999) - 1]


def phase(options, server, base, prefix, log, launched, seconds, offered_rate, hot_connections=CONNECTIONS):
    preopened = offered_rate is None and options.profile == "preopened"
    timing = prefix.with_suffix(".client.txt")
    raw = prefix.with_suffix(".burst.json" if preopened else ".load.txt" if offered_rate is None else ".vegeta.bin")
    command = ["taskset", "-c", options.load_mask, "/usr/bin/time", "-o", timing, "-f", LOAD_TIMING]
    if preopened:
        command += ["env", "GOMAXPROCS=2", options.keepalive_client, "-address", base.removeprefix("http://"),
                    "-duration", f"{seconds}s", "-connections", str(CONNECTIONS),
                    "-hot-connections", str(hot_connections)]
        targets = None
    elif offered_rate is None:
        command += ["wrk", "-s", SCRIPTS / "wrk-stats.lua", "--latency", "-t2", f"-c{CONNECTIONS}",
                    f"-d{seconds}s", "--timeout", "2s"]
        if options.profile == "churn":
            command += ["-H", "Connection: close"]
        command.append(f"{base}/json")
        targets = None
    else:
        command += ["vegeta", "attack", f"-rate={offered_rate}/s", f"-duration={seconds}s",
                    f"-workers={CONNECTIONS}", f"-max-workers={CONNECTIONS}", f"-connections={CONNECTIONS}",
                    f"-max-connections={CONNECTIONS}", "-timeout=2s", "-http2=false", "-redirects=-1", "-max-body=0"]
        if options.profile == "churn":
            command.append("-keepalive=false")
        targets = f"GET {base}/json\n".encode()

    before, _, rss_before = proc_sample(server.pid)
    started = time.monotonic()
    if preopened:
        stopped = Event()
        observations = []
        observer = Thread(target=observe_epoll, args=(server.pid, started, seconds, stopped, observations))
        observer.start()
    try:
        with raw.open("wb") as output, prefix.with_suffix(".stderr.txt").open("wb") as stderr:
            subprocess.run(command, input=targets, stdout=output, stderr=stderr, check=True, timeout=seconds + 10)
    finally:
        if preopened:
            stopped.set()
            observer.join()
            prefix.with_suffix(".epoll.json").write_text(json.dumps(observations, indent=2) + "\n")
    ended = time.monotonic()
    after, peak, rss_after = proc_sample(server.pid)
    workers = ACTIVE_WORKERS.findall(log.read_text())
    surviving = None
    setup_s = None
    post_scale_rps = None
    windows = []
    if preopened:
        stats = json.loads(raw.read_text())
        peers = stats["connections"]
        counts = stats["per_second_requests"]
        requests = stats["requests"]
        errors = sum(bool(peer.get("error")) for peer in peers)
        surviving = sum(not peer.get("error") for peer in peers)
        duration = stats["duration_us"] / 1_000_000
        setup_s = stats["setup_us"] / 1_000_000
        valid = (
            requests > 0
            and errors == 0
            and len({peer["peer"] for peer in peers}) == surviving == len(peers) == CONNECTIONS
            and sum(peer["hot"] for peer in peers) == hot_connections
            and all((peer["completed"] > 0) == peer["hot"] for peer in peers)
            and sum(peer["completed"] for peer in peers) == sum(counts) == requests
            and duration == seconds and len(counts) == ceil(seconds)
        )
        if seconds > POST_SCALE_SECOND:
            post_scale_rps = round(sum(counts[POST_SCALE_SECOND:]) / (seconds - POST_SCALE_SECOND), 3)
        latencies = {key: stats[key] for key in ("p50_us", "p95_us", "p99_us", "p999_us")}
        windows = stats["latency_windows"]
        valid = valid and sum(window["requests"] for window in windows) == requests
    elif offered_rate is None:
        stats = read_stats(raw)
        requests = int(stats["requests"]) - int(stats["http_errors"])
        errors = error_count(stats)
        duration = int(stats["duration_us"]) / 1_000_000
        valid = requests > 0 and errors == 0
        latencies = {key: float(stats[key]) for key in ("p50_us", "p95_us", "p99_us")}
        latencies["p999_us"] = None
    else:
        report = prefix.with_suffix(".vegeta.json")
        with report.open("wb") as output:
            subprocess.run(["vegeta", "report", "-type=json", raw], stdout=output, check=True)
        summary = read_report(report, offered_rate * seconds)
        windows, p999_us = vegeta_windows(raw, seconds)
        subprocess.run(["gzip", "-n", raw], check=True)
        requests = summary["ok"]
        errors = summary["attempted"] - requests
        duration = seconds
        valid = summary["valid"] and sum(window["requests"] for window in windows) == summary["attempted"]
        latencies = {key: summary["latencies"][percentile] / 1000 for key, percentile in
                     (("p50_us", "50th"), ("p95_us", "95th"), ("p99_us", "99th"))}
        latencies["p999_us"] = p999_us
    if windows:
        prefix.with_suffix(".windows.json").write_text(json.dumps(windows, indent=2) + "\n")
    populated = [window for window in windows if window["requests"]]
    worst_p99 = max(populated, key=lambda window: window["p99_us"], default=None)
    worst_p999 = max(populated, key=lambda window: window["p999_us"], default=None)
    status = Path(f"/proc/{server.pid}/status").read_text()
    threads = int(re.search(r"^Threads:\s+(\d+)", status, re.MULTILINE).group(1))
    cpu = after - before
    return {
        "start_s": round(started - launched, 3), "end_s": round(ended - launched, 3),
        "offered_rps": offered_rate, "duration_s": duration, "accepted_responses": requests, "errors": errors,
        "rps": round(requests / duration, 3), **latencies,
        "worst_window_p99_us": worst_p99["p99_us"] if worst_p99 else None,
        "worst_window_p99_samples": worst_p99["requests"] if worst_p99 else None,
        "worst_window_p99_start_us": worst_p99["start_us"] if worst_p99 else None,
        "worst_window_p999_us": worst_p999["p999_us"] if worst_p999 else None,
        "worst_window_p999_samples": worst_p999["requests"] if worst_p999 else None,
        "worst_window_p999_start_us": worst_p999["start_us"] if worst_p999 else None,
        "valid": int(valid),
        "cpu_seconds": round(cpu, 6), "cpu_seconds_per_million": round(cpu / requests * 1e6, 3) if valid else None,
        "rss_before_kib": rss_before, "rss_after_kib": rss_after, "process_peak_rss_kib": peak,
        "threads_after": threads, "descriptors_after": len(os.listdir(f"/proc/{server.pid}/fd")),
        "preopened_connections_surviving": surviving, "preopened_setup_s": setup_s, "post_5s_rps": post_scale_rps,
        "preopened_hot_connections": hot_connections if preopened else None,
        "active_workers_after": int(workers[-1]) if workers else None, **read_load_timing(timing),
    }


def trial(options, variant, repetition):
    work = options.output / f"{variant}-{repetition}"
    work.mkdir()
    port = available_port()
    base = f"http://127.0.0.1:{port}"
    env = {key: value for key, value in os.environ.items() if not key.startswith("KTSERVE_")}
    env.pop("BENCHMARK_SUITE", None)
    env.update(BIND_HOST="127.0.0.1", PORT=str(port),
               KTSERVE_BACKEND="epoll", KTSERVE_WORKER_LISTENER="shared", no_proxy="127.0.0.1", NO_PROXY="127.0.0.1")
    if variant != EAGER:
        env["KTSERVE_WORKERS"] = str(VARIANTS.get(variant, 1))
    if options.profile == "preopened":
        env["KTSERVE_EPOLL_APPLICATION_WAKEUP"] = "eventfd"
    if variant in ("adaptive", HANDOFF):
        env["KTSERVE_ADAPTIVE_WORKERS"] = "1"
    if variant == STANDBY:
        env["KTSERVE_ADAPTIVE_WORKERS"] = variant
    if variant == HANDOFF:
        env["KTSERVE_ADAPTIVE_HANDOFF"] = "1"
    log = work / "server.log"
    launched = time.monotonic()
    with log.open("wb") as output:
        server = subprocess.Popen(["taskset", "-c", options.server_mask, options.binary], env=env,
                                  stdout=output, stderr=subprocess.STDOUT)
    rows = []
    try:
        health(server, port)
        with (work / "smoke.txt").open("wb") as output:
            subprocess.run(["bash", SCRIPTS / "smoke.sh", base], env=env, stdout=output, check=True, timeout=15)
        phases = [
            ("warmup", options.warmup, options.quiet_rate),
            ("quiet-before", options.quiet, options.quiet_rate),
        ]
        if options.profile == "preopened":
            phases.extend([
                ("short-burst", options.short_burst_ms / 1000, None),
                ("short-recovery", 7, options.quiet_rate),
                ("matched-burst-before", 2, options.matched_burst_rate),
                ("rearmed", 2, options.quiet_rate),
            ])
        phases.extend([
            ("burst", options.burst, None),
            ("recovery", options.recovery, options.quiet_rate),
            ("quiet-after", options.quiet, options.quiet_rate),
        ])
        if options.profile == "preopened":
            phases.extend([("matched-burst-after", 2, options.matched_burst_rate), ("skew-burst", 2, None)])
        for name, seconds, offered_rate in phases:
            hot_connections = SKEW_HOT_CONNECTIONS if name == "skew-burst" else CONNECTIONS
            row = phase(options, server, base, work / name, log, launched, seconds, offered_rate, hot_connections)
            rows.append({"variant": variant, "repetition": repetition, "phase": name, **row})
        health(server, port)
        log_text = log.read_text()
        expected = "ktServe backend=epoll tcp=ipv4"
        if variant in ("fixed-2", EAGER):
            expected += " workers=2"
        if variant == EAGER and "ktServe worker_policy=eager workers=2" not in log_text.splitlines():
            raise RuntimeError(f"The default did not select two eager workers in {log}")
        if variant == STANDBY and "ktServe adaptive worker_policy=standby" not in log_text.splitlines():
            raise RuntimeError(f"Missing standby worker-policy announcement in {log}")
        if variant in ("adaptive", HANDOFF, STANDBY):
            if ACTIVE_WORKERS.findall(log_text)[:1] != ["1"]:
                raise RuntimeError(f"Missing initial adaptive worker announcement in {log}")
            if variant == HANDOFF and "ktServe adaptive handoff=enabled" not in log_text.splitlines():
                raise RuntimeError(f"Missing handoff configuration announcement in {log}")
            if variant == STANDBY and not re.search(r"^ktServe adaptive standby_ready=2\b", log_text, re.MULTILINE):
                raise RuntimeError(f"Missing prestarted standby worker in {log}")
        elif expected not in log_text.splitlines():
            raise RuntimeError(f"Missing fixed worker announcement in {log}")
    finally:
        exit_status = stop(server)
    if exit_status != 0:
        raise RuntimeError(f"ktServe exited with {exit_status}. See {log}")
    with (work / "phases.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)
    return rows


def transition_window(trial_directory, rows, elapsed):
    # Server elapsed time starts after launch, while load time starts before spawning
    # the client. This identifies a nearby aggregate window, not an exact alignment.
    for row in rows:
        offset_us = round((elapsed - row["start_s"] - (row["preopened_setup_s"] or 0)) * 1_000_000)
        if not 0 <= offset_us < row["duration_s"] * 1_000_000:
            continue
        path = trial_directory / f"{row['phase']}.windows.json"
        if not path.exists():
            return None
        for window in json.loads(path.read_text()):
            if window["requests"] and window["start_us"] <= offset_us < window["start_us"] + window["duration_us"]:
                return (f"{row['phase']} +{window['start_us'] / 1_000_000:.2f}s "
                        f"p99 {window['p99_us'] / 1000:.3f} ms "
                        f"and p99.9 {window['p999_us'] / 1000:.3f} ms (n={window['requests']:,})")
    return None


def report(output, profile, variants, rows, repetitions):
    with (output / "phases.csv").open("w", newline="") as data:
        writer = csv.DictWriter(data, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)

    preopened = profile == "preopened"
    profile_note = (
        "The burst uses 64 TCP connections that already completed an HTTP/1.1 response before the timer started. "
        "The load client never reconnects. The raw burst JSON reports each stream's request count and error status. "
        "The skewed burst drives only 8 connections and re-verifies all 64 when it ends. "
        "The epoll snapshots show per-loop socket registrations before and after the expected scale-up."
        if preopened else
        "Each phase starts a new load process. Persistent burst connections are reused within the burst."
    )
    lines = [f"### Adaptive workers — {profile}", "",
             "Exploratory HTTP/1.1 `/json` load on a shared Linux host with disjoint CPU masks. " + profile_note, "",
             "| Workers | Phase | Valid runs | Errors | Requests/s, median (min–max) | p99 / p99.9 ms | Worst window p99 / p99.9 ms (samples) | Server CPU s | End RSS MiB | Load CPUs used | Steady burst req/s after 5s |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    phase_names = dict.fromkeys(row["phase"] for row in rows if row["phase"] not in ("warmup", "rearmed"))
    for variant in variants:
        for name in phase_names:
            attempts = [row for row in rows if row["variant"] == variant and row["phase"] == name]
            group = [row for row in attempts if row["valid"]]
            if group:
                rates = [row["rps"] for row in group]
                steady = [row["post_5s_rps"] for row in group if row["post_5s_rps"] is not None]
                p999 = [row["p999_us"] for row in group if row["p999_us"] is not None]
                tails = []
                for percentile in ("p99", "p999"):
                    key = f"worst_window_{percentile}"
                    worst = max((row for row in group if row[f"{key}_us"] is not None),
                                key=lambda row: row[f"{key}_us"], default=None)
                    tails.append(f"{worst[f'{key}_us'] / 1000:.3f} ({worst[f'{key}_samples']:,})" if worst else "—")
                typical_p999 = f"{median(p999) / 1000:.3f}" if p999 else "—"
                values = [f"{median(rates):,.0f} ({min(rates):,.0f}–{max(rates):,.0f})",
                          f"{median(row['p99_us'] for row in group) / 1000:.3f} / {typical_p999}",
                          " / ".join(tails),
                          f"{median(row['cpu_seconds'] for row in group):.3f}",
                          f"{median(row['rss_after_kib'] for row in group) / 1024:.2f}",
                          f"{median(row['load_cpu_equivalents'] for row in group):.2f}",
                          f"{median(steady):,.0f}" if steady else "—"]
            else:
                values = ["—"] * 7
            errors = sum(row["errors"] for row in attempts)
            lines.append("| " + " | ".join([variant, name, f"{len(group)}/{repetitions}", str(errors), *values]) + " |")
    lines.extend(["", "Adaptive worker events by trial, timed from server startup. "
                  "Bracketed tails are approximate matching client windows.", ""])
    for variant in ("adaptive", HANDOFF, STANDBY):
        if variant not in variants:
            continue
        for index in range(1, repetitions + 1):
            directory = output / f"{variant}-{index}"
            log = (directory / "server.log").read_text()
            trial_rows = [row for row in rows if row["variant"] == variant and row["repetition"] == index]
            transitions = []
            for event, elapsed in ADAPTIVE_EVENTS.findall(log):
                detail = f"`{event}`"
                if elapsed:
                    seconds = int(elapsed) / 1000
                    detail += f" at {seconds:.2f} s"
                    if event.startswith(("active_workers=2", "parked_worker=", "worker_cleanup_ms=")):
                        window = transition_window(directory, trial_rows, seconds)
                        if window:
                            detail += f" [{window}]"
                transitions.append(detail)
            if "max_workers=1 reason=budget" in log:
                transitions.append("CPU budget limited the process to one worker")
            if "worker_failed=2" in log:
                transitions.append("worker two failed, see the server log")
            lines.append(f"- {variant} trial {index} — {', '.join(transitions)}.")
    burst_validation = (
        "Each valid burst serves all 64 original sockets with byte-identical validated responses and no I/O errors. "
        if preopened else
        "wrk counts completed responses and flags HTTP status codes of 400 or higher. "
    )
    lines.extend(["",
                  "Whole-phase latency, CPU and RSS use medians of valid runs. " + burst_validation +
                  "The worst-window columns select the maximum of the observed window percentiles across valid runs. "
                  "Preopened windows are 250 ms and bucket requests by completion. Vegeta windows are one second "
                  "and bucket requests by start time. Small windows make p99.9 sensitive to single observations. "
                  "Vegeta requires HTTP 200 and delivery within 2% of the offered load. Its error counts are failed "
                  "HTTP requests; preopened error counts are failed original streams. "
                  "Load CPU equivalents compare to a budget of two logical CPUs. A busy generator or a shared host "
                  "limits attribution to the server.", ""])
    markdown = "\n".join(lines)
    (output / "summary.md").write_text(markdown)
    print(markdown)


def main(options):
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 4:
        raise SystemExit("Use four available logical CPUs for disjoint server and load affinity")
    options.server_mask, options.load_mask = [",".join(map(str, group)) for group in (cpus[:2], cpus[2:4])]
    options.output.mkdir(parents=True, exist_ok=False)
    with options.binary.open("rb") as binary:
        digest = file_digest(binary, "sha256").hexdigest()
    variants = ["fixed-1", "fixed-2", EAGER, STANDBY, HANDOFF] if options.profile == "preopened" else list(VARIANTS)
    environment = {**vars(options), "scope": "exploratory same-host Linux loopback", "variants": variants,
                   "binary_sha256": digest, "commit": subprocess.check_output(
                       ["git", "-C", SCRIPTS.parent, "rev-parse", "HEAD"], text=True).strip(),
                   "kernel": os.uname().release, "architecture": os.uname().machine,
                   "os": Path("/etc/os-release").read_text(),
                   "cpu": subprocess.check_output(["lscpu"], text=True),
                   "vegeta": subprocess.check_output(["vegeta", "-version"], text=True).strip(),
                   "wrk": subprocess.run(["wrk", "--version"], stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, text=True).stdout.splitlines()[0],
                   "endpoint": "/json", "http": "1.1", "connections": CONNECTIONS}
    if options.profile == "preopened":
        with options.keepalive_client.open("rb") as client:
            environment["keepalive_client_sha256"] = file_digest(client, "sha256").hexdigest()
        environment["application_wakeup"] = "eventfd"
        environment["skew_hot_connections"] = SKEW_HOT_CONNECTIONS
    (options.output / "environment.json").write_text(json.dumps(environment, default=str, indent=2) + "\n")
    with (options.output / "cpu-topology.json").open("wb") as topology:
        subprocess.run(["python3", SCRIPTS / "worker-topology.py", options.server_mask, options.load_mask],
                       stdout=topology, check=True)
    rows = []
    for repetition in range(1, options.repetitions + 1):
        rotation = (repetition - 1) % len(variants)
        for variant in variants[rotation:] + variants[:rotation]:
            print(f"{options.profile} {variant} trial {repetition}", flush=True)
            rows.extend(trial(options, variant, repetition))
    report(options.output, options.profile, variants, rows, options.repetitions)
    if options.profile == "preopened" and any(not row["valid"] for row in rows):
        raise SystemExit("At least one run failed delivery, response or socket-continuity checks. See the raw phases.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--profile", choices=("persistent", "churn", "preopened"), required=True)
    parser.add_argument("--keepalive-client", type=Path, help="compiled scripts/fixtures/keepalive-burst.go")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--quiet-rate", type=int, default=800)
    parser.add_argument("--warmup", type=int, default=4)
    parser.add_argument("--quiet", type=int, default=5)
    parser.add_argument("--burst", type=int, default=12)
    parser.add_argument("--recovery", type=int, default=13)
    parser.add_argument("--short-burst-ms", type=int, default=500)
    parser.add_argument("--matched-burst-rate", type=int, default=20_000)
    options = parser.parse_args()
    if min(options.repetitions, options.quiet_rate, options.warmup, options.quiet, options.burst, options.recovery,
           options.short_burst_ms, options.matched_burst_rate) < 1:
        parser.error("Use positive trial settings")
    options.binary = options.binary.resolve()
    if not options.binary.is_file() or not os.access(options.binary, os.X_OK):
        parser.error(f"Expected executable at {options.binary}")
    if options.profile == "preopened":
        if options.keepalive_client is None or not options.keepalive_client.is_file() or not os.access(options.keepalive_client, os.X_OK):
            parser.error("Build scripts/fixtures/keepalive-burst.go and pass its executable with --keepalive-client")
        options.keepalive_client = options.keepalive_client.resolve()
    main(options)
