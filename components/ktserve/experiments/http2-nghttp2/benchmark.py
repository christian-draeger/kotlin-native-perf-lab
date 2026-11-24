#!/usr/bin/env python3
"""Run matching exploratory HTTP/2 /json trials for ktServe and Axum on Linux."""

import argparse
import csv
import gzip
import math
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
EXPECTED = '{"id":42,"name":"benchmark-user","active":true}\n2 200 application/json'
CONNECTIONS = 16
STREAM_COUNTS = (1, 8)
VARIANTS = ("ktserve", "ktserve-nodelay", "axum")
LOAD_THREADS = 2


def command_output(*command):
    return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True).strip()


def wait_for_json(server, url, path, certificate=None):
    protocol = ["--http2", "--cacert", str(certificate)] if certificate else ["--http2-prior-knowledge"]
    deadline = time.monotonic() + 10
    while server.poll() is None and time.monotonic() < deadline:
        response = subprocess.run([
            "curl", "--noproxy", "*", *protocol, "-fsS", "--max-time", "1",
            "--write-out", "\n%{http_version} %{http_code} %{content_type}", url,
        ], capture_output=True, text=True)
        if response.returncode == 0:
            path.write_text(response.stdout + "\n")
            if response.stdout == EXPECTED:
                return
            raise ValueError(f"Expected HTTP/2 200 and the exact JSON payload. See {path}")
        time.sleep(0.1)
    raise RuntimeError(f"Server never returned /json. See the server log next to {path}")


def load(log, prefix, url, streams, duration, requests=None):
    tls = url.startswith("https://")
    protocol = ["--alpn-list=h2"] if tls else ["-p", "h2c"]
    env = {**os.environ, "LC_ALL": "C"}
    if tls:
        # The hosted h2load aborted in libev's epoll teardown with concurrent TLS streams.
        env["LIBEV_FLAGS"] = "2"  # EVBACKEND_POLL
    command = [
        "taskset", "-c", prefix, "/usr/bin/time", "-f", "load_time %U %S %e %M",
        "h2load", *protocol, "-c", str(CONNECTIONS), "-m", str(streams),
        "-t", str(LOAD_THREADS), "-D", str(duration), "-N", "2",
    ]
    if requests is not None:
        command.append(f"--log-file={requests}")
    with log.open("w") as output:
        subprocess.run([*command, url], stdout=output, stderr=subprocess.STDOUT,
                       timeout=duration + 15, check=True, env=env)
    return log.read_text()


def negotiated_protocol(log, tls):
    application = re.search(r"^Application protocol: (h2c?)$", log, re.M)
    version = re.search(r"^TLS Protocol: (TLSv[\d.]+)$", log, re.M)
    if not application or (tls and (application[1] != "h2" or not version)) or (not tls and version):
        raise ValueError("h2load did not report the expected HTTP/2 and TLS negotiation")
    return application[1], version[1] if version else ""


def load_result(log, requests, tls):
    rate = re.search(r"finished in .*?, ([\d.]+) req/s", log)
    counts = re.search(r"requests: \d+ total, \d+ started, (\d+) done, (\d+) succeeded, "
                       r"(\d+) failed, (\d+) errored, (\d+) timeout", log)
    usage = re.search(r"^load_time ([\d.]+) ([\d.]+) ([\d.]+) (\d+)$", log, re.M)
    protocol, tls_version = negotiated_protocol(log, tls)
    if not (rate and counts and usage):
        raise ValueError("Missing request counts, throughput or load process timing")
    done, successful, failed, errored, timed_out = map(int, counts.groups())
    user, system, wall, peak = map(float, usage.groups())
    latencies = []
    unexpected_statuses = 0
    with requests.open() as rows:
        for row in rows:
            _, status, elapsed, *_ = row.split()
            if status == "200":
                latencies.append(int(elapsed))
            else:
                unexpected_statuses += 1
    latencies.sort()
    valid = int(len(latencies) == successful == done > 0 and not (
        failed or errored or timed_out or unexpected_statuses
    ))
    percentiles = {
        f"p{p}_ms": latencies[math.ceil(len(latencies) * p / 100) - 1] / 1000 if latencies else ""
        for p in (50, 95, 99)
    }
    with requests.open("rb") as raw, gzip.open(str(requests) + ".gz", "wb") as compressed:
        shutil.copyfileobj(raw, compressed)
    requests.unlink()
    return {
        "protocol": protocol, "tls_version": tls_version,
        "requests_per_second": float(rate[1]), **percentiles, "completed": done, "successes": successful,
        "failed": failed, "errored": errored, "timeouts": timed_out, "unexpected_statuses": unexpected_statuses,
        "load_cpu_seconds": round(user + system, 3), "load_peak_rss_kib": int(peak),
        "load_cpu_equivalents": round((user + system) / wall, 3) if wall else "", "valid": valid,
    }


def reserve_port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def launcher(args, variant, port):
    if variant == "axum":
        return [str(args.axum)]
    command = [str(args.ktserve), str(port)]
    if variant == "ktserve-nodelay":
        command.append("--tcp-nodelay")
    return command


def verify_socket_options(args):
    for variant in ("ktserve", "ktserve-nodelay"):
        port = reserve_port()
        path = args.output / f"{variant}.socket-options.log"
        trace = ["strace", "-f", "-qq", "-e", "trace=accept,accept4,setsockopt", "-o", str(path)]
        with path.with_suffix(".server.log").open("w") as output:
            server = subprocess.Popen([*trace, *launcher(args, variant, port)],
                                      stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                url = f"http://127.0.0.1:{port}/json"
                wait_for_json(server, url, path.with_suffix(".preflight.log"))
            finally:
                if server.poll() is None:
                    os.killpg(server.pid, signal.SIGTERM)
                    try:
                        server.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(server.pid, signal.SIGKILL)
                        server.wait()
        calls = path.read_text()
        accepted = set(re.findall(
            r"^(?:\d+\s+)?accept(?:4)?\(\d+, NULL, NULL(?:, [^)]*)?\)\s+= (\d+)$", calls, re.M
        ))
        enabled = set(re.findall(
            r"^(?:\d+\s+)?setsockopt\((\d+), (?:SOL_TCP|IPPROTO_TCP), TCP_NODELAY, \[1\], 4\)\s+= 0$", calls, re.M
        ))
        expected = accepted if variant == "ktserve-nodelay" else set()
        if not accepted or (accepted & enabled) != expected:
            raise ValueError(f"Unexpected TCP_NODELAY state for accepted sockets. See {path}")
        print(f"{variant}: accepted-socket configuration verified with {path.name}", flush=True)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def create_certificate(directory):
    certificate, key = directory / "cert.pem", directory / "key.pem"
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
        "-nodes", "-days", "1", "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
        "-keyout", str(key), "-out", str(certificate),
    ], check=True, capture_output=True)
    return certificate, key


@contextmanager
def ingress(args, upstream, server_cpus, log, tls):
    if tls is None:
        yield None, f"http://127.0.0.1:{upstream}"
        return
    certificate, key = tls
    port = reserve_port()
    env = {
        **os.environ, "GOMAXPROCS": "2",
        "KTSERVE_TLS_PORT": str(port), "KTSERVE_TLS_HOST": "127.0.0.1",
        "KTSERVE_TLS_BIND": "127.0.0.1", "KTSERVE_H2C_PORT": str(upstream),
        "KTSERVE_TLS_CERT": str(certificate), "KTSERVE_TLS_KEY": str(key),
        "XDG_DATA_HOME": str(certificate.parent / "data"), "XDG_CONFIG_HOME": str(certificate.parent / "config"),
    }
    config = Path(__file__).with_name("ingress") / "Caddyfile"
    with log.open("w") as output:
        process = subprocess.Popen([
            "taskset", "-c", server_cpus, str(args.caddy), "run", "--config", str(config), "--adapter", "caddyfile",
        ], stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            yield process, f"https://127.0.0.1:{port}"
        finally:
            stop(process)


def trial(args, variant, streams, repetition, server_cpus, load_cpus, tls):
    label = args.output / f"{variant}-{streams}streams-{repetition}"
    port = reserve_port()
    command = launcher(args, variant, port)
    env = {**os.environ, "BIND_HOST": "127.0.0.1", "PORT": str(port), "TOKIO_WORKER_THREADS": "2"}
    metrics = [sys.executable, str(ROOT / "scripts/proc_metrics.py")]
    with label.with_suffix(".server.log").open("w") as output:
        server = subprocess.Popen(["taskset", "-c", server_cpus, *command],
                                  stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            preflight = ".origin-preflight.log" if tls else ".preflight.log"
            wait_for_json(server, f"http://127.0.0.1:{port}/json", label.with_suffix(preflight))
            with ingress(args, port, server_cpus, label.with_suffix(".ingress.log"), tls) as (proxy, base):
                url = base + "/json"
                if proxy:
                    wait_for_json(proxy, url, label.with_suffix(".preflight.log"), tls[0])
                warmup = load(label.with_suffix(".warmup.log"), load_cpus, url, streams, args.warmup)
                negotiated_protocol(warmup, tls is not None)
                before, *_ = command_output(*metrics, str(server.pid)).split()
                proxy_before = command_output(*metrics, str(proxy.pid)).split()[0] if proxy else None
                requests = label.with_suffix(".requests.tsv")
                measured = load(label.with_suffix(".h2load.log"), load_cpus, url, streams, args.duration, requests)
                after, peak, resident = command_output(*metrics, str(server.pid)).split()
                proxy_stats = {}
                if proxy:
                    proxy_after, proxy_peak, proxy_resident = command_output(*metrics, str(proxy.pid)).split()
                    proxy_stats = {
                        "ingress_cpu_seconds": round(float(proxy_after) - float(proxy_before), 6),
                        "ingress_peak_rss_kib": int(proxy_peak), "ingress_rss_kib": int(proxy_resident),
                    }
        finally:
            stop(server)
    return {
        "variant": variant, "endpoint": "/json", "connections": CONNECTIONS, "streams": streams,
        "run": repetition, "warmup_s": args.warmup, "target_duration_s": args.duration, "port": port,
        "tls_mode": "caddy-termination" if tls else "off",
        "tcp_nodelay": "on" if variant == "ktserve-nodelay" else "default",
        "server_cpu_seconds": round(float(after) - float(before), 6), "peak_rss_kib": int(peak),
        "rss_kib": int(resident), **proxy_stats, **load_result(measured, requests, tls is not None),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ktserve", "axum", "output"):
        parser.add_argument(f"--{name}", type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--caddy", type=lambda value: Path(value).resolve(), help="Run over a shared HTTPS ingress configuration")
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--duration", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if min(args.warmup, args.duration, args.repeats) < 1:
        parser.error("Warm-up, duration and repeats must be positive integers")
    if not all(os.access(binary, os.X_OK) for binary in (args.ktserve, args.axum)):
        parser.error("Build both release executables first")
    if args.caddy and not os.access(args.caddy, os.X_OK):
        parser.error("Provide a Caddy executable for the TLS pilot")
    if sys.platform != "linux":
        parser.error("This pilot uses Linux process and CPU affinity metrics")
    cpus = sorted(os.sched_getaffinity(0))
    if len(cpus) < 2 + LOAD_THREADS:
        parser.error("The pilot needs two logical CPUs for the server and two for the load generator")
    server_cpus = f"{cpus[0]},{cpus[1]}"
    load_cpus = ",".join(map(str, cpus[2:2 + LOAD_THREADS]))
    args.output.mkdir(parents=True, exist_ok=False)
    sha = command_output("git", "-C", str(ROOT), "rev-parse", "HEAD")
    status = command_output("git", "-C", str(ROOT), "status", "--porcelain")
    metadata = (
        f"git_sha={sha}\ngit_worktree={'dirty' if status else 'clean'}\nmode=exploratory; same VM\n"
        f"protocol={'HTTP/2 ALPN with h2c upstream' if args.caddy else 'h2c prior knowledge'}\n"
        f"TLS={'Caddy termination' if args.caddy else 'off'}\nserver_cpus={server_cpus}\nload_cpus={load_cpus}\n"
        f"load_threads={LOAD_THREADS}\nTOKIO_WORKER_THREADS=2\n"
        "ktserve_launch=PORT\nktserve_nodelay_launch=PORT --tcp-nodelay\n"
        "axum_launch=PORT environment variable with http2 Cargo feature\n"
    )
    with (args.output / "environment.txt").open("w") as output:
        output.write(metadata)
        for command in (
            ("uname", "-srm"), ("lscpu",), ("lscpu", "-e"), ("h2load", "--version"),
            ("curl", "--version"), ("sha256sum", str(args.ktserve), str(args.axum)),
        ):
            output.write(command_output(*command) + "\n")
        if args.caddy:
            output.write("h2load_libev_backend=poll\n")
            output.write(command_output(str(args.caddy), "version") + "\n")
            output.write(command_output("openssl", "version") + "\n")
    verify_socket_options(args)
    invalid = False
    writer = None
    # Keep the private test key outside of the archived output directory.
    with tempfile.TemporaryDirectory(prefix="ktserve-https-") as directory, \
            (args.output / "results.csv").open("w", newline="") as output:
        tls = create_certificate(Path(directory)) if args.caddy else None
        for repetition in range(1, args.repeats + 1):
            for cell, streams in enumerate(STREAM_COUNTS):
                offset = (repetition - 1 + cell) % len(VARIANTS)
                for variant in VARIANTS[offset:] + VARIANTS[:offset]:
                    row = trial(args, variant, streams, repetition, server_cpus, load_cpus, tls)
                    if writer is None:
                        writer = csv.DictWriter(output, fieldnames=row.keys())
                        writer.writeheader()
                    writer.writerow(row)
                    output.flush()
                    invalid |= row["valid"] == 0
                    print(f"{variant} {streams} streams run {repetition}: "
                          f"{row['requests_per_second']:.2f} req/s, p99 {row['p99_ms']} ms, valid={row['valid']}",
                          flush=True)
    if invalid:
        raise SystemExit(f"HTTP/2 pilot contains invalid trials. See {args.output}")


if __name__ == "__main__":
    main()
