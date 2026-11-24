#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
source "$ROOT/scripts/benchmark-load.sh"

out="${1:?Usage: benchmark-epoll-trigger.sh OUTPUT_DIRECTORY STRESS_FIXTURE}"
fixture="${2:?Provide the stress release executable}"
test "$(uname -s)" = Linux
test -x "$fixture"
mkdir -p "$out"
test ! -e "$out/results.csv"
unset LOAD_SSH
export LC_ALL=C BIND_HOST=127.0.0.1 NO_PROXY=127.0.0.1 no_proxy=127.0.0.1
export BENCHMARK_SUITE=application KTSERVE_BACKEND=epoll KTSERVE_WORKER_DIAGNOSTICS=0

runs="${RUNS:-3}"
warmup="${WARMUP:-5}"
duration="${DURATION:-5}"
for value in "$runs" "$warmup" "$duration"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo 'Use positive integer trial settings.' >&2; exit 2; }
done
connections=64
threads=2
profiles=(1-shared 2-shared 2-reuseport)
modes=(level edge)

cpus="$(python3 - <<'PY'
import os

cpus = sorted(os.sched_getaffinity(0))
if len(cpus) < 4:
    raise SystemExit("The pilot needs at least four available logical CPUs")
print(",".join(map(str, cpus[:2])), ",".join(map(str, cpus[2:4])))
PY
)"
read -r SERVER_CPUS LOAD_CPUS <<< "$cpus"
export SERVER_CPUS LOAD_CPUS
python3 "$ROOT/scripts/worker-topology.py" "$SERVER_CPUS" "$LOAD_CPUS" > "$out/cpu-topology.json"

pick_port() {
  python3 - <<'PY'
import socket

with socket.socket() as server:
    server.bind(("127.0.0.1", 0))
    print(server.getsockname()[1])
PY
}

start_trial_server() {
  local mode="$1" profile="$2" prefix="$3"
  export KTSERVE_EPOLL_TRIGGER="$mode"
  export KTSERVE_WORKERS="${profile%%-*}" KTSERVE_WORKER_LISTENER="${profile#*-}"
  PORT="$(pick_port)"
  export PORT
  start_server ktserve "$prefix.server.txt"
  grep -Fxq "ktServe epoll peer_trigger=$mode" "$prefix.server.txt"
  if [[ "$KTSERVE_WORKERS" == 2 ]]; then
    grep -Fq 'workers=2' "$prefix.server.txt"
    grep -Fq "worker_listener=$KTSERVE_WORKER_LISTENER" "$prefix.server.txt"
  fi
  "$ROOT/scripts/smoke.sh" "http://$BIND_HOST:$PORT" > "$prefix.smoke.txt" 2>&1
}

trap 'stop_server; cleanup_load' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
prepare_load
git_sha="$(git -C "$ROOT" rev-parse HEAD)"
benchmark_source=local-linux
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then benchmark_source=github-hosted-actions; fi
{
  printf 'git_sha=%s\nbenchmark_suite=epoll-trigger\nmachine=%s\nkernel=%s\n' "$git_sha" "$(uname -m)" "$(uname -r)"
  printf 'benchmark_source=%s\nci_run_id=%s\nci_run_attempt=%s\n' \
    "$benchmark_source" "${GITHUB_RUN_ID:-}" "${GITHUB_RUN_ATTEMPT:-}"
  if [[ -z "$(git -C "$ROOT" status --porcelain)" ]]; then
    echo 'git_worktree=clean'
  else
    echo 'git_worktree=dirty'
  fi
  printf 'server_affinity=%s\nload_affinity=%s\nconnections=%s\nthreads=%s\n' \
    "$SERVER_CPUS" "$LOAD_CPUS" "$connections" "$threads"
  printf 'runs=%s\nwarmup_s=%s\nduration_s=%s\n' "$runs" "$warmup" "$duration"
  cat /etc/os-release
  lscpu
  uname -a
  grep -m1 'kotlin("multiplatform") version' "$ROOT/components/ktserve/build.gradle.kts"
  grep -m1 '^distributionUrl=' "$ROOT/components/ktserve/gradle/wrapper/gradle-wrapper.properties"
  sha256sum "$ROOT/components/ktserve/build/bin/native/releaseExecutable/ktserve.kexe" "$fixture"
  load_environment
} > "$out/environment.txt" 2>&1

for mode in "${modes[@]}"; do
  # Check real transport cancellation, limits and bidirectional streaming before load.
  KTSERVE_EPOLL_TRIGGER="$mode" python3 "$ROOT/scripts/tests/ktserve_stress.py" "$fixture" > "$out/$mode-stress.txt" 2>&1
  for profile in "${profiles[@]}"; do
    prefix="$out/$mode-$profile-check"
    start_trial_server "$mode" "$profile" "$prefix"
    python3 "$ROOT/scripts/tests/epoll_trigger_smoke.py" "$PORT" "$server_pid" > "$prefix.batch.txt" 2>&1
    wait "$server_pid"
    server_pid=''
  done
done

wrk_args=(--latency -t "$threads" -c "$connections" --timeout 2s)
for ((run = 1; run <= runs; run++)); do
  for profile in "${profiles[@]}"; do
    for endpoint in /json /catalog/123; do
      for index in "${!modes[@]}"; do
        mode="${modes[(run - 1 + index) % ${#modes[@]}]}"
        prefix="$out/$mode-$profile-${endpoint//\//_}-$run"
        start_trial_server "$mode" "$profile" "$prefix"
        url="http://$BIND_HOST:$PORT$endpoint"
        run_load "${wrk_args[@]}" -d "${warmup}s" "$url" > "$prefix.warmup.wrk.txt" 2>&1
        read -r before _ _ < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
        run_load "${wrk_args[@]}" -d "${duration}s" "$url" > "$prefix.wrk.txt" 2>&1
        read -r after peak rss < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
        python3 "$ROOT/scripts/parse_wrk.py" --log "$prefix.wrk.txt" --warmup-log "$prefix.warmup.wrk.txt" \
          --csv "$out/results.csv" --variant "ktserve-$profile-$mode" --endpoint "$endpoint" --run "$run" \
          --git-sha "$git_sha" --connections "$connections" --threads "$threads" --duration "$duration" \
          --warmup "$warmup" --cpu-start "$before" --cpu-end "$after" --peak-rss-kib "$peak" --rss-kib "$rss"
        stop_server
      done
    done
  done
done

python3 "$ROOT/scripts/summarize.py" "$out/results.csv" | tee "$out/summary.md"
python3 - "$out/results.csv" <<'PY'
import csv
import sys

with open(sys.argv[1]) as source:
    invalid = [row for row in csv.DictReader(source) if row["valid"] != "1"]
if invalid:
    raise SystemExit(f"{len(invalid)} trials were invalid. See the raw logs.")
PY
