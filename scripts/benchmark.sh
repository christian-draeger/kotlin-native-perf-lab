#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
[[ "$(uname -s)" == Linux ]] || { echo 'This /proc benchmark runs on Linux.' >&2; exit 1; }
for command in wrk curl python3 git; do
  command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 1; }
done
RUNS="${RUNS:-5}"
WARMUP="${WARMUP:-10}"
DURATION="${DURATION:-30}"
THREADS="${THREADS:-4}"
CONNECTIONS="${CONNECTIONS:-64}"
for value in "$RUNS" "$WARMUP" "$DURATION" "$THREADS" "$CONNECTIONS"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo 'Run parameters must be positive integers.' >&2; exit 2; }
done
(( CONNECTIONS % THREADS == 0 )) || { echo 'CONNECTIONS must be a multiple of THREADS.' >&2; exit 2; }
variants=(ktor-jvm ktor-native rust-axum kotlin-reactor)
endpoints=(/health /json /users/123)
# Allocate distinct ports before any trial. After load, accepted connections can
# remain in TIME_WAIT and prevent a different runtime from binding the same port.
ports="$(python3 - "$(( RUNS * ${#variants[@]} * ${#endpoints[@]} ))" "${PORT:-0}" <<'PY'
import contextlib
import socket
import sys

count, start = map(int, sys.argv[1:])
if not 0 <= start <= 65535 or start and start + count - 1 > 65535:
    sys.exit("PORT must leave enough room for one port per trial.")
with contextlib.ExitStack() as sockets:
    for index in range(count):
        probe = sockets.enter_context(socket.socket())
        probe.bind(("127.0.0.1", start + index if start else 0))
        print(probe.getsockname()[1])
PY
)"
mapfile -t trial_ports <<< "$ports"
trial_index=0
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
results_root="${BENCHMARK_RESULTS_DIR:-$ROOT/results}"
OUT="$results_root/$STAMP"
mkdir -p "$results_root"
mkdir "$OUT"
echo "Recording runs in $OUT"
GIT_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unavailable)"
{
  echo "timestamp_utc=$STAMP"
  echo "git_sha=$GIT_SHA"
  if [[ "$GIT_SHA" != unavailable ]]; then
    if [[ -z "$(git -C "$ROOT" status --porcelain)" ]]; then
      echo 'git_worktree=clean'
    else
      echo 'git_worktree=dirty'
    fi
  fi
  printf 'kernel=%s\n' "$(uname -sr)"
  printf 'machine=%s\n' "$(uname -m)"
  cat /etc/os-release
  lscpu
  awk '/^MemTotal:/ {print}' /proc/meminfo
  for field in cpu.max cpuset.cpus.effective memory.max; do
    if [[ -r "/sys/fs/cgroup/$field" ]]; then
      printf '%s=%s\n' "$field" "$(cat "/sys/fs/cgroup/$field")"
    fi
  done
  java ${JAVA_OPTS:-} -XX:+PrintCommandLineFlags -version 2>&1
  rustc -Vv
  cargo --version
  if [[ -x "$ROOT/ktor/gradlew" ]]; then
    "$ROOT/ktor/gradlew" --version
  else
    gradle --version
  fi
  wrk --version 2>&1 | sed -n '1p' || true
  python3 --version
  sha256sum "$ROOT"/ktor/build/jvmDist/app.jar "$ROOT"/ktor/build/jvmDist/lib/*.jar \
    "$ROOT"/ktor/build/bin/native/releaseExecutable/*.kexe "$ROOT"/rust-axum/target/release/axum-baseline \
    "$ROOT"/kotlin-reactor/build/bin/native/releaseExecutable/kotlin-reactor.kexe |
    sed "s|  $ROOT/|  |"
  echo "runs=$RUNS warmup_s=$WARMUP target_duration_s=$DURATION threads=$THREADS connections=$CONNECTIONS timeout_s=2"
  echo 'port_strategy=distinct TCP port per trial'
  echo 'load_generator=local HTTP/1.1 loopback; same host and CPU pool as servers'
} > "$OUT/environment.txt"

trap stop_server EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
wrk_args=(--latency -s "$ROOT/scripts/wrk-stats.lua" -t "$THREADS" -c "$CONNECTIONS" --timeout 2s)

for ((run = 1; run <= RUNS; run++)); do
  for endpoint in "${endpoints[@]}"; do
    for ((offset = 0; offset < ${#variants[@]}; offset++)); do
      variant="${variants[(run - 1 + offset) % ${#variants[@]}]}"
      prefix="$OUT/${variant}_${endpoint//\//_}_$run"
      export PORT="${trial_ports[trial_index]}"
      trial_index=$((trial_index + 1))
      base="http://127.0.0.1:$PORT"
      printf '%s %s %s %s\n' "$run" "$endpoint" "$variant" "$PORT" >> "$OUT/ports.txt"
      start_server "$variant" "$prefix.server.txt"
      "$ROOT/scripts/smoke.sh" "$base" > "$prefix.smoke.txt"
      wrk "${wrk_args[@]}" -d "${WARMUP}s" "$base$endpoint" > "$prefix.warmup.wrk.txt" 2>&1
      read -r cpu_start _ _ < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
      wrk "${wrk_args[@]}" -d "${DURATION}s" "$base$endpoint" > "$prefix.wrk.txt" 2>&1
      read -r cpu_end peak_rss_kib rss_kib < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
      python3 "$ROOT/scripts/parse_wrk.py" \
        --log "$prefix.wrk.txt" --warmup-log "$prefix.warmup.wrk.txt" --csv "$OUT/results.csv" \
        --variant "$variant" --endpoint "$endpoint" --run "$run" --connections "$CONNECTIONS" \
        --threads "$THREADS" --duration "$DURATION" --warmup "$WARMUP" --cpu-start "$cpu_start" \
        --cpu-end "$cpu_end" --peak-rss-kib "$peak_rss_kib" --rss-kib "$rss_kib" --git-sha "$GIT_SHA"
      stop_server
    done
  done
done
python3 "$ROOT/scripts/summarize.py" "$OUT/results.csv" > "$OUT/summary.md"
echo "Summary: $OUT/summary.md"
