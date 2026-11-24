#!/usr/bin/env bash
set -euo pipefail

export KTSERVE_IO_BINARY=${1:?Specify the validated release executable}
output=${2:?Specify a results directory}
export BENCHMARK_SUITE=io-backends RUNS=3 WARMUP=5 DURATION=5 THREADS=2
cpus="$(python3 - <<'PY'
import os
ids = sorted(os.sched_getaffinity(0))
if len(ids) < 4:
    raise SystemExit("The exploratory pair requires four logical CPUs.")
print(",".join(map(str, ids[:2])), ",".join(map(str, ids[2:4])))
PY
)"
read -r SERVER_CPUS LOAD_CPUS <<<"$cpus"
export SERVER_CPUS LOAD_CPUS
for connections in 16 64; do
  for endpoint in json users; do
    route="/$endpoint"
    if [[ "$endpoint" == users ]]; then route=/users/123; fi
    CONNECTIONS="$connections" BENCHMARK_ENDPOINT="$route" \
      BENCHMARK_RESULTS_DIR="$output/$endpoint-$connections" bash scripts/benchmark.sh
  done
done
