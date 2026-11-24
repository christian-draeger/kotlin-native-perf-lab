#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
source "$ROOT/scripts/benchmark-load.sh"

out="${1:?Usage: benchmark-cpu-budget.sh OUTPUT_DIRECTORY}"
[[ "$(uname -s)" == Linux ]]
for program in curl git jcmd python3 taskset timeout; do
  command -v "$program" >/dev/null || { echo "Missing $program" >&2; exit 1; }
done
mkdir -p "$out"
[[ ! -e "$out/results.csv" ]]
unset LOAD_SSH BENCHMARK_SUITE
export LC_ALL=C BIND_HOST=127.0.0.1 NO_PROXY=127.0.0.1 no_proxy=127.0.0.1
export KTSERVE_WORKER_LISTENER=shared TOKIO_WORKER_THREADS=2
base_java_opts="${JAVA_OPTS:-}"
runs="${RUNS:-3}"
warmup="${WARMUP:-15}"
duration="${DURATION:-5}"
for count in "$runs" "$warmup" "$duration"; do
  [[ "$count" =~ ^[1-9][0-9]*$ ]] || { echo 'Use positive integer trial settings.' >&2; exit 2; }
done
variants=(ktserve-1-worker micronaut-1-io-thread axum-2-tokio-workers ktserve-2-workers micronaut-2-io-threads)
threads=2
connections=64

cpu_masks="$(python3 - <<'PY'
import os
cpus = sorted(os.sched_getaffinity(0))
if len(cpus) < 4:
    raise SystemExit("The CPU-budget comparison requires at least four available logical CPUs.")
print(",".join(map(str, cpus[:2])), ",".join(map(str, cpus[2:4])))
PY
)"
read -r SERVER_CPUS LOAD_CPUS <<< "$cpu_masks"
export SERVER_CPUS LOAD_CPUS
python3 "$ROOT/scripts/worker-topology.py" "$SERVER_CPUS" "$LOAD_CPUS" > "$out/cpu-topology.json"

port_list="$(python3 - "$((runs * ${#variants[@]}))" <<'PY'
from contextlib import ExitStack
import socket
import sys
with ExitStack() as sockets:
    for _ in range(int(sys.argv[1])):
        sock = sockets.enter_context(socket.socket())
        sock.bind(("127.0.0.1", 0))
        print(sock.getsockname()[1])
PY
)"
mapfile -t ports <<< "$port_list"

trap 'stop_server; cleanup_load' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
prepare_load
git_sha="$(git -C "$ROOT" rev-parse HEAD)"
{
  printf 'git_sha=%s\nbenchmark_suite=cpu-budget\nbenchmark_endpoint=/json\n' "$git_sha"
  printf 'machine=%s\nkernel=%s\n' "$(uname -m)" "$(uname -r)"
  printf 'server_affinity=%s\nload_affinity=%s\n' "$SERVER_CPUS" "$LOAD_CPUS"
  printf 'runs=%s warmup_s=%s duration_s=%s threads=%s connections=%s\n' "$runs" "$warmup" "$duration" "$threads" "$connections"
  printf 'micronaut_event_loop_group=perflab\nmicronaut_acceptor_threads=1\naxum_tokio_worker_threads=2\n'
  printf 'protocol=HTTP/1.1 persistent connections\nktserve_two_worker_listener=shared\n'
  cat /etc/os-release
  lscpu
  java -version
  load_environment
} > "$out/environment.txt" 2>&1
(
  cd "$ROOT"
  sha256sum components/ktserve/build/bin/native/releaseExecutable/ktserve.kexe \
    competitors/rust-axum/target/release/axum-baseline \
    competitors/kotlin-jvm/micronaut/target/app.jar \
    competitors/kotlin-jvm/micronaut/target/lib/*.jar
) > "$out/application.sha256"

verify_workers() {
  local variant="$1" prefix="$2" task worker_count expected
  for task in /proc/"$server_pid"/task/*; do
    awk '/^Name:|^Cpus_allowed_list:/ {print}' "$task/status" 2>/dev/null || true
  done > "$prefix.tasks.txt"
  case "$variant" in
    ktserve-1-worker)
      grep -Fxq 'ktServe backend=epoll tcp=ipv4' "$prefix.server.txt"
      ;;
    ktserve-2-workers)
      grep -Fxq 'ktServe backend=epoll tcp=ipv4 workers=2' "$prefix.server.txt"
      grep -Fxq 'ktServe worker_listener=shared' "$prefix.server.txt"
      ;;
    micronaut-*)
      expected=1
      if [[ "$variant" == micronaut-2-* ]]; then expected=2; fi
      timeout 10s jcmd "$server_pid" Thread.print > "$prefix.netty-threads.txt" 2>&1
      worker_count="$(grep -cE '^"perflab-[[:alnum:]]*[Ee]ventLoopGroup-[0-9]+-[0-9]+"' "$prefix.netty-threads.txt" || true)"
      if [[ "$worker_count" != "$expected" ]]; then
        printf 'Expected %s actual Micronaut worker event loops, observed %s. See %s.netty-threads.txt\n' "$expected" "$worker_count" "$prefix" >&2
        return 1
      fi
      ;;
    axum-2-tokio-workers)
      worker_count="$(grep -cE '^Name:[[:space:]]+tokio-rt-worker$' "$prefix.tasks.txt" || true)"
      if [[ "$worker_count" != 2 ]]; then
        printf 'Expected two actual Tokio workers, observed %s. See %s.tasks.txt\n' "$worker_count" "$prefix" >&2
        return 1
      fi
      ;;
  esac
}

index=0
wrk_args=(--latency -t "$threads" -c "$connections" --timeout 2s)
for ((run = 1; run <= runs; run++)); do
  for ((offset = 0; offset < ${#variants[@]}; offset++)); do
    variant="${variants[(run - 1 + offset) % ${#variants[@]}]}"
    export PORT="${ports[index]}"
    index=$((index + 1))
    base="http://$BIND_HOST:$PORT"
    prefix="$out/$variant-run-$run"
    export JAVA_OPTS="$base_java_opts"
    case "$variant" in
      ktserve-1-worker)
        export KTSERVE_WORKERS=1
        launcher=ktserve
        ;;
      ktserve-2-workers) launcher=ktserve-workers-2 ;;
      axum-2-tokio-workers) launcher=rust-axum ;;
      micronaut-*)
        count=1
        if [[ "$variant" == micronaut-2-* ]]; then count=2; fi
        export JAVA_OPTS="$base_java_opts -Dmicronaut.server.netty.worker.event-loop-group=perflab -Dmicronaut.netty.event-loops.perflab.num-threads=$count -Dmicronaut.server.netty.parent.threads=1"
        launcher=micronaut
        ;;
    esac
    start_server "$launcher" "$prefix.server.txt"
    bash "$ROOT/scripts/smoke.sh" "$base" > "$prefix.smoke.txt"
    run_load "${wrk_args[@]}" -d "${warmup}s" "$base/json" > "$prefix.warmup.wrk.txt" 2>&1
    read -r before _ _ < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
    run_load "${wrk_args[@]}" -d "${duration}s" "$base/json" > "$prefix.wrk.txt" 2>&1
    read -r after peak rss < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
    verify_workers "$variant" "$prefix"
    python3 "$ROOT/scripts/parse_wrk.py" --log "$prefix.wrk.txt" --warmup-log "$prefix.warmup.wrk.txt" \
      --csv "$out/results.csv" --variant "$variant" --endpoint /json --run "$run" --git-sha "$git_sha" \
      --connections "$connections" --threads "$threads" --duration "$duration" --warmup "$warmup" \
      --cpu-start "$before" --cpu-end "$after" --peak-rss-kib "$peak" --rss-kib "$rss"
    stop_server
  done
done

python3 "$ROOT/scripts/summarize.py" "$out/results.csv" | tee "$out/summary.md"
python3 - "$out/results.csv" <<'PY'
import csv
import sys
with open(sys.argv[1]) as file:
    invalid = [row for row in csv.DictReader(file) if row["valid"] != "1"]
if invalid:
    raise SystemExit(f"{len(invalid)} trials were invalid. See the raw logs.")
PY
