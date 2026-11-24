import csv
import hashlib
import importlib.util
import os
from pathlib import Path
import platform
from types import SimpleNamespace
import subprocess
import sys

sys.path.insert(0, "/src/scripts")
spec = importlib.util.spec_from_file_location("upstream", "/src/scripts/benchmark-upstream.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
out = Path("/results")
available = sorted(os.sched_getaffinity(0))
assert len(available) >= 4
server, load, origin = available[:2], available[2:3], available[3:4]
args = SimpleNamespace(precondition=15, warmup=10, duration=8)
metadata = out / "environment.txt"
with metadata.open("w") as report:
    report.write("scope=local exploratory OrbStack ARM64 Linux, one Mac shared by frontend, origin, and generator\n")
    report.write("host_cpu_model=" + os.environ["LOCAL_HOST_CPU"] + "\n")
    report.write(f"source_sha={os.environ['SOURCE_REF']}\narch={platform.machine()}\nkernel={platform.release()}\n")
    report.write(f"server_cpus={server}\nload_cpus={load}\norigin_cpus={origin}\n")
    report.write("rate=800\nrepeats=2\nprecondition=15s\nwarmup=10s\nmeasurement=8s\n")
    report.write("Kotlin Native=2.4.21\nCIO=3.6.1\nRust=1.100.0\nAxum=0.8.9\n")
    report.flush()
    for command in (["lscpu"], ["k6", "version"], [sys.executable, "--version"]):
        subprocess.run(command, stdout=report, stderr=subprocess.STDOUT, check=True)
    for binary in (probe.PROBE, Path("/src/competitors/rust-axum/target/release/axum-baseline")):
        with binary.open("rb") as stream:
            report.write(f"{hashlib.file_digest(stream, 'sha256').hexdigest()} {binary}\n")
origin_port = probe.open_port()
csv_path = out / "upstream.csv"
with probe.launch([sys.executable, "/src/scripts/upstream-origin.py", str(origin_port)], origin, out/"origin.log") as dependency:
    probe.ready(dependency, origin_port, "/stats")
    for repeat, order in enumerate((("ktserve", "rust-axum"), ("rust-axum", "ktserve")), 1):
        for variant in order:
            prefix = out / f"{variant}-800-{repeat}"
            port = probe.open_port()
            with probe.launch(probe.VARIANTS[variant], server, prefix.with_suffix(".server.log"),
                              PORT=str(port), BIND_HOST="127.0.0.1", BENCHMARK_SUITE="application-upstream",
                              BENCHMARK_UPSTREAM_PORT=str(origin_port)) as frontend:
                probe.ready(frontend, port, "/health")
                probe.preflight(port, origin_port, prefix.with_suffix(".parity.json"))
                rows = list(probe.measure(args, load, dependency, origin_port, frontend, port, prefix, variant, 800, repeat))
            is_first = not csv_path.exists()
            with csv_path.open("a", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=rows[0])
                if is_first:
                    writer.writeheader()
                writer.writerows(rows)
            first = rows[0]
            print(f"{variant} repeat {repeat}: valid={first['valid']} requests={first['http_requests']} drops={first['dropped_arrivals']} mismatches={first['response_mismatches']}", flush=True)

