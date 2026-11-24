#!/usr/bin/env python3
"""Record Linux CPU topology and select alternate SMT affinity masks when available."""

import argparse
import itertools
import json
import os
from pathlib import Path


def cpu_ids(mask):
    result = set()
    for item in mask.split(","):
        first, separator, last = item.partition("-")
        start = int(first)
        result.update(range(start, int(last) + 1) if separator else (start,))
    return result


def topology():
    result = {}
    for cpu in sorted(os.sched_getaffinity(0)):
        root = Path(f"/sys/devices/system/cpu/cpu{cpu}/topology")
        try:
            result[cpu] = {
                "package": int((root / "physical_package_id").read_text()),
                "core": int((root / "core_id").read_text()),
                "siblings": sorted(cpu_ids((root / "thread_siblings_list").read_text().strip())),
            }
        except (OSError, ValueError):
            result[cpu] = None
    return result


def shares_core(ids, cpus):
    if len(ids) != 2 or any(cpus.get(cpu) is None for cpu in ids):
        return None
    first, second = tuple(ids)
    return second in cpus[first]["siblings"]


def alternative(server, load, cpus):
    if len(server) != 2 or len(load) != 2 or server & load:
        return None
    initial = shares_core(server, cpus)
    if initial is None:
        return None
    total = server | load
    for combination in itertools.combinations(sorted(total), 2):
        candidate = set(combination)
        complement = total - candidate
        both = shares_core(candidate, cpus), shares_core(complement, cpus)
        if both == (not initial, not initial):
            return sorted(candidate), sorted(complement)
    return None


def mask(cpus):
    return ",".join(map(str, sorted(cpus)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--alternative", action="store_true", help="Print alternate server and load masks if available")
    parser.add_argument("server")
    parser.add_argument("load")
    args = parser.parse_args()
    cpus = topology()
    server, load = cpu_ids(args.server), cpu_ids(args.load)
    if args.alternative:
        other = alternative(server, load, cpus)
        if other is not None:
            print(mask(other[0]), mask(other[1]))
        return
    print(json.dumps({
        "server_cpus": sorted(server),
        "load_cpus": sorted(load),
        "server_cpus_share_core": shares_core(server, cpus),
        "load_cpus_share_core": shares_core(load, cpus),
        "visible_cpu_topology": cpus,
    }, indent=2))


if __name__ == "__main__":
    main()
