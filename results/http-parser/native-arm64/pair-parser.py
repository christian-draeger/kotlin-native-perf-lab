from hashlib import file_digest
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
import urllib.request

root = Path("/probe")
scripts = Path("/scripts")
output = root / "result"
output.mkdir()
binaries = {label: root / label / "ktserve.kexe" for label in ("old", "candidate")}
revisions = dict(zip(binaries, sys.argv[1:3], strict=True))
cpus = sorted(os.sched_getaffinity(0))
assert len(cpus) == 4, cpus
server_cpus, load_cpus = cpus[:2], cpus[2:]
connections = 64
threads = 2
warmup_seconds = 5
measure_seconds = 8
repetitions = 5
endpoints = {"/health": b"ok", "/json": b'{"id":42,"name":"benchmark-user","active":true}'}

def shell(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()

def digest(path):
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()

def bind(cpus):
    return lambda: os.sched_setaffinity(0, cpus)

def wait_until_ready(process, endpoint, expected):
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError("ktServe exited during startup")
        try:
            with urllib.request.urlopen("http://127.0.0.1:18080" + endpoint, timeout=0.3) as response:
                assert response.status == 200
                assert response.read() == expected
                return
        except OSError:
            time.sleep(0.05)
    raise RuntimeError("ktServe failed to start")

def drive(port, endpoint, duration, log):
    args = [
        "/usr/bin/time", "-f", "perflab_load user_s=%U system_s=%S elapsed_s=%e peak_rss_kib=%M",
        "wrk", "--latency", "-s", str(scripts / "wrk-stats.lua"), "--timeout", "2s",
        "-d", f"{duration}s", "-t", str(threads), "-c", str(connections),
        f"http://127.0.0.1:{port}{endpoint}",
    ]
    with log.open("w") as stream:
        subprocess.run(args, check=True, stdout=stream, stderr=subprocess.STDOUT, preexec_fn=bind(load_cpus))

metadata = {
    "scope": "Exploratory matched Linux ARM64 loopback on one OrbStack VM",
    "host_cpu": os.environ["KTSERVE_PARSE_CPU_MODEL"],
    "host_os": os.environ["KTSERVE_PARSE_HOST_OS"],
    "container_image": os.environ["KTSERVE_PARSE_IMAGE"],
    "kernel": platform.release(),
    "architecture": platform.machine(),
    "container_os": Path("/etc/os-release").read_text(),
    "cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
    "server_cpus": server_cpus,
    "load_cpus": load_cpus,
    "kotlin_native": "2.4.21",
    "toolchains": shell("dpkg-query", "-W", "wrk", "time", "python3"),
    "connections": connections,
    "load_threads": threads,
    "server_configuration": "one epoll worker, HTTP/1.1, level-triggered sockets",
    "warmup_seconds": warmup_seconds,
    "measurement_seconds": measure_seconds,
    "repetitions": repetitions,
    "identities": {
        label: {"git_sha": revisions[label], "binary_sha256": digest(path)}
        for label, path in binaries.items()
    },
    "measurement_scripts": {
        path.name: digest(path)
        for path in (Path(__file__), scripts / "wrk-stats.lua", scripts / "proc_metrics.py", scripts / "parse_wrk.py")
    },
}
(output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")

env = {
    **os.environ, "PORT": "18080", "BIND_HOST": "127.0.0.1", "KTSERVE_WORKERS": "1",
    "KTSERVE_EPOLL_TRIGGER": "level", "KTSERVE_EPOLL_APPLICATION_WAKEUP": "poll", "KTSERVE_EPOLL_TCP_NODELAY": "default",
}
for repeat in range(1, repetitions + 1):
    for endpoint, expected in endpoints.items():
        order = list(binaries)
        if (repeat + list(endpoints).index(endpoint)) % 2 == 0:
            order.reverse()
        for label in order:
            name = f"{label}-{endpoint[1:]}-{repeat}"
            warmup = output / (name + ".warmup.log")
            measured = output / (name + ".measure.log")
            with (output / (name + ".server.log")).open("w") as stream:
                with subprocess.Popen([binaries[label]], env=env, stdout=stream, stderr=subprocess.STDOUT,
                                      preexec_fn=bind(server_cpus)) as process:
                    try:
                        wait_until_ready(process, endpoint, expected)
                        drive(18080, endpoint, warmup_seconds, warmup)
                        cpu_start, _, _ = shell(sys.executable, scripts / "proc_metrics.py", str(process.pid)).split()
                        drive(18080, endpoint, measure_seconds, measured)
                        cpu_end, peak, current = shell(sys.executable, scripts / "proc_metrics.py", str(process.pid)).split()
                    finally:
                        process.terminate()
                        assert process.wait(timeout=5) == 0
            subprocess.run([
                sys.executable, str(scripts / "parse_wrk.py"), "--log", str(measured), "--warmup-log", str(warmup),
                "--csv", str(output / "results.csv"), "--variant", label, "--git-sha", revisions[label],
                "--endpoint", endpoint, "--run", str(repeat), "--connections", str(connections),
                "--threads", str(threads), "--duration", str(measure_seconds), "--warmup", str(warmup_seconds),
                "--cpu-start", cpu_start, "--cpu-end", cpu_end, "--peak-rss-kib", peak, "--rss-kib", current,
            ], check=True)
