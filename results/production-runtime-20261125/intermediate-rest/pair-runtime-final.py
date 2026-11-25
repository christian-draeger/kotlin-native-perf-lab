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
output = root / "final-results"
sys.path.insert(0, str(scripts))
spec = spec_from_file_location("benchmark", scripts / "benchmark-integrations.py")
benchmark = module_from_spec(spec)
spec.loader.exec_module(benchmark)

server_cpus = [8, 9]
load_cpus = [10, 11]
assert set(server_cpus + load_cpus) <= os.sched_getaffinity(0)
def pin(cpus):
    return [str(root / "pin-cpus"), ",".join(map(str, cpus))]

rates = [800]
repeats = 4
binaries = {tag: root / f"{tag}.kexe" for tag in ("deadline", "rest-final")}
shas = {'deadline': 'ed0b0b5c87231ebde376a981dc8f1858126fedb3', 'rest-final': '3c4de960155dc5565efd7f0810ac9fd3a3081fd9'}
for tag in binaries:
    (output / tag).mkdir()
(output / "calibration").mkdir()

args = SimpleNamespace(
    family="application-mix", application_profile="capacity", load_tool="k6",
    compare_native_wakeup=False, compare_native_nodelay=False, rates=rates, vus=96,
    threads=2, warmup=5, precondition=5, duration=15, executable=root/"deadline/run-server",
    output=output/"calibration", calibration_server=root/"traffic-calibration",
)
environment = {
    "scope": "Quiet follow-up on the final REST release using the unchanged capacity metric functions",
    "cpu_model": os.environ["HOST_CPU_MODEL"], "platform": platform.platform(),
    "os_release": Path("/etc/os-release").read_text(), "kernel": platform.release(),
    "kernel_cpuinfo": Path("/proc/cpuinfo").read_text(), "affinity_server": server_cpus, "affinity_load": load_cpus,
    "rates": rates, "duration_s": args.duration, "warmup_s": args.warmup, "precondition_s": args.precondition,
    "repetitions": repeats, "vus_per_scenario": args.vus, "load_tool": subprocess.check_output(["k6", "version"], text=True).strip(),
    "process_time_tool": subprocess.check_output(["/usr/bin/time", "--version"], text=True).splitlines()[0],
    "calibration_toolchain": os.environ["CALIBRATION_TOOLCHAIN"],
    "server_configuration": "One default worker, epoll level trigger, no enabled eventfd wakeup or TCP_NODELAY",
    "release_kotlin_version": "2.4.21",
    "image_digest": os.environ["KTSERVE_BENCH_IMAGE_DIGEST"],
    "cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
    "commits": shas,
}
paths = [
    *binaries.values(), root / "k6", root / "traffic-calibration", scripts / "benchmark-integrations.py",
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
            print(tag, flush=True)
            args.output = output / tag
            args.executable = root / tag / "run-server"
            benchmark.trial_k6(args, shas[tag], "ktserve", rate, run,
                               pin(server_cpus), pin(load_cpus), calibration[rate])
