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
output = root / "archive" / "followup"
sys.path.insert(0, str(scripts))
spec = spec_from_file_location("benchmark", scripts / "benchmark-integrations.py")
benchmark = module_from_spec(spec)
spec.loader.exec_module(benchmark)

server_cpus = [0, 1]
load_cpus = [2, 3]
assert set(server_cpus + load_cpus) <= os.sched_getaffinity(0)
def pin(cpus):
    return [str(root / "pin-cpus"), ",".join(map(str, cpus))]

rates = [3200]
repeats = 3
binaries = {"old": root / "old/ktserve.kexe", "candidate": root / "candidate/ktserve.kexe"}
shas = {
    "old": "35d8e938e2590a7c6221af2e7c7c0a8f432978bb",
    "candidate": "aaf0da265238a28000ebdd52fc092b0eabaf7a23",
}
for tag in binaries:
    (output / tag).mkdir()
(output / "calibration").mkdir()

args = SimpleNamespace(
    family="application-mix", application_profile="capacity", load_tool="k6",
    compare_native_wakeup=False, compare_native_nodelay=False, rates=rates, vus=96,
    threads=2, warmup=5, precondition=5, duration=15, executable=root/"old/run-server",
    output=output/"calibration", calibration_server=root/"traffic-calibration",
)
environment = {
    "scope": "Local Linux ARM64 loopback diagnostic using the unchanged production capacity metric functions",
    "cpu_model": os.environ["HOST_CPU_MODEL"], "platform": platform.platform(),
    "os_release": Path("/etc/os-release").read_text(), "kernel": platform.release(),
    "kernel_cpuinfo": Path("/proc/cpuinfo").read_text(), "affinity_server": server_cpus, "affinity_load": load_cpus,
    "rates": rates, "duration_s": args.duration, "warmup_s": args.warmup, "precondition_s": args.precondition,
    "repetitions": repeats, "vus_per_scenario": args.vus, "load_tool": subprocess.check_output(["k6", "version"], text=True).strip(),
    "process_time_tool": subprocess.check_output(["/usr/bin/time", "--version"], text=True).splitlines()[0],
    "calibration_toolchain": os.environ["CALIBRATION_TOOLCHAIN"],
    "docker_image_digest": os.environ["IMAGE_DIGEST"],
    "server_configuration": "One worker, epoll level trigger, no enabled eventfd wakeup or TCP_NODELAY",
    "release_kotlin_version": "2.4.21",
    "cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
    "commits": shas,
}
paths = [
    *binaries.values(), root / "traffic-calibration", scripts / "benchmark-integrations.py",
    scripts / "k6-integration.js", Path(__file__),
]
with (output / "input.sha256").open("w") as manifest:
    for path in paths:
        with path.open("rb") as source:
            print(hashlib.file_digest(source, "sha256").hexdigest(), path.as_posix(), file=manifest)
(output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
calibration = benchmark.calibrate_traffic(args, pin(server_cpus), pin(load_cpus))
for run in range(1, repeats + 1):
    for tier, rate in enumerate(rates):
        variants = tuple(binaries)
        if (run + tier) % 2 == 0:
            variants = tuple(reversed(variants))
        for tag in variants:
            print(tag, "before" if tag == "old" else "after", flush=True)
            args.output = output / tag
            args.executable = root / tag / "run-server"
            benchmark.trial_k6(args, shas[tag], "ktserve", rate, run,
                               pin(server_cpus), pin(load_cpus), calibration[rate])
