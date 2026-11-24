#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"

out="${1:?Usage: benchmark-worker-latency.sh OUTPUT_DIRECTORY}"
: "${SERVER_CPUS:?Specify server CPU affinity}"
: "${LOAD_CPUS:?Specify load CPU affinity}"
export BENCHMARK_SUITE=workers
export BIND_HOST="${BIND_HOST:-127.0.0.1}"
mkdir -p "$out"
python3 "$ROOT/scripts/worker-topology.py" "$SERVER_CPUS" "$LOAD_CPUS" > "$out/topology.json"
{
  git -C "$ROOT" rev-parse HEAD
  printf 'reference_sha=%s\nserver_cpus=%s\nload_cpus=%s\n' "$NATIVESERVE_REFERENCE_SHA" "$SERVER_CPUS" "$LOAD_CPUS"
  printf '%s\n' 'Exploratory same-host comparison. HTTP/1.1 /json, at most 64 connections, 4s warm-up, 5s measured.'
  vegeta -version
} > "$out/environment.txt"
trap stop_server EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

available_port() {
  python3 - "$BIND_HOST" <<'PY'
import socket
import sys
with socket.socket() as sock:
    sock.bind((sys.argv[1], 0))
    print(sock.getsockname()[1])
PY
}

unset NATIVESERVE_WORKER_DIAGNOSTICS
variants=(nativeserve-reference kotlin-reactor nativeserve-workers-2 rust-axum)
for rate in 20000 40000; do
  for run in 1 2 3; do
    for offset in "${!variants[@]}"; do
      variant="${variants[(run - 1 + offset) % ${#variants[@]}]}"
      prefix="$out/${variant}_${rate}_$run"
      PORT="$(available_port)"
      export PORT
      base="http://$BIND_HOST:$PORT"
      start_server "$variant" "$prefix.server.txt"
      "$ROOT/scripts/smoke.sh" "$base" > "$prefix.smoke.txt"
      attack=(taskset -c "$LOAD_CPUS" vegeta attack "-rate=$rate/s" -workers=64 -max-workers=64
        -connections=64 -max-connections=64 -timeout=2s -http2=false -max-body=0)
      printf 'GET %s/json\n' "$base" | "${attack[@]}" -duration=4s | vegeta report -type=json > "$prefix.warmup.json"
      read -r start _ _ < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
      printf 'GET %s/json\n' "$base" | "${attack[@]}" -duration=5s > "$prefix.bin"
      read -r end peak rss < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
      printf '%s,%s,%s,%s\n' "$start" "$end" "$peak" "$rss" > "$prefix.proc.csv"
      stop_server
      vegeta report -type=json "$prefix.bin" > "$prefix.json"
      gzip -n "$prefix.bin"
    done
  done
done

PORT="$(available_port)"
export PORT NATIVESERVE_WORKER_DIAGNOSTICS=1
start_server nativeserve-workers-2 "$out/worker-distribution.server.txt"
printf 'GET http://%s:%s/json\n' "$BIND_HOST" "$PORT" |
  taskset -c "$LOAD_CPUS" vegeta attack -rate=20000/s -duration=3s -workers=64 -max-workers=64 \
    -connections=64 -max-connections=64 -timeout=2s -http2=false -max-body=0 |
  vegeta report -type=json > "$out/worker-distribution.load.json"
stop_server
grep -E '^NativeServe worker=[0-9]+ accepted=[0-9]+ requests=[0-9]+$' "$out/worker-distribution.server.txt" \
  > "$out/worker-distribution.txt"
test "$(wc -l < "$out/worker-distribution.txt")" -eq 2
