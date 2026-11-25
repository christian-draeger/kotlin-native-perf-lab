from importlib.util import module_from_spec, spec_from_file_location
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
from types import SimpleNamespace

root = Path("/validation")
scripts = Path("/scripts")
output = root / "combined-core"
sys.path.insert(0, str(scripts))
spec = spec_from_file_location("benchmark", scripts / "benchmark-integrations.py")
benchmark = module_from_spec(spec)
spec.loader.exec_module(benchmark)

server_cpus = [0, 1]
load_cpus = [2, 3]
assert set(server_cpus + load_cpus) <= os.sched_getaffinity(0)
def pin(cpus):
    return [str(root / "pin-cpus"), ",".join(map(str, cpus))]

rates = [800]
repeats = 5
binaries = {tag: root / f"{tag}.kexe" for tag in ("pre-production", "production-final")}
shas = {'pre-production': '35d8e938e2590a7c6221af2e7c7c0a8f432978bb', 'production-final': 'cdec4ca0269a0769ca682567bb9042ea39feb2fe'}
for tag in binaries:
    (output / tag).mkdir()

args = SimpleNamespace(
    family="application-mix", application_profile="capacity", load_tool="wrk",
    compare_native_wakeup=False, compare_native_nodelay=False, rates=rates, vus=96,
    threads=2, connections=16, warmup=6, precondition=0, duration=8, executable=root/"pre-production/run-server",
    output=output/"pre-production", calibration_server=root/"traffic-calibration",
)
environment = {
    "scope": "Saturated JSON check of both exact milestone release commits, using the existing wrk benchmark functions",
    "cpu_model": os.environ["HOST_CPU_MODEL"], "platform": platform.platform(),
    "os_release": Path("/etc/os-release").read_text(), "kernel": platform.release(),
    "kernel_cpuinfo": Path("/proc/cpuinfo").read_text(), "affinity_server": server_cpus, "affinity_load": load_cpus,
    "endpoint": "/json", "connections": args.connections, "duration_s": args.duration, "warmup_s": args.warmup, "precondition_s": args.precondition,
    "repetitions": repeats, "load_tool": subprocess.run(["wrk", "--version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.splitlines()[0],
    "process_time_tool": subprocess.check_output(["/usr/bin/time", "--version"], text=True).splitlines()[0],
    "server_configuration": "One default worker, epoll level trigger, no enabled eventfd wakeup or TCP_NODELAY",
    "release_kotlin_version": "2.4.21",
    "image_digest": os.environ["KTSERVE_BENCH_IMAGE_DIGEST"],
    "cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
    "commits": shas,
}
paths = [
    *binaries.values(), scripts / "benchmark-integrations.py", scripts / "parse_wrk.py",
    scripts / "wrk-stats.lua", Path(__file__),
]
with (output / "input.sha256").open("w") as manifest:
    for path in paths:
        with path.open("rb") as source:
            print(hashlib.file_digest(source, "sha256").hexdigest(), path.as_posix(), file=manifest)
(output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")

for run in range(1, repeats + 1):
    variants = tuple(binaries)
    if run % 2 == 0:
        variants = tuple(reversed(variants))
    for tag in variants:
        print(tag, flush=True)
        args.output = output / tag
        args.executable = root / tag / "run-server"
        benchmark.trial(args, shas[tag], "ktserve", "/json", run, pin(server_cpus), pin(load_cpus))
