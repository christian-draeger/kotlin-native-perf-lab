#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(uname -s)" == Linux ]] || { echo 'This /proc benchmark runs on Linux.' >&2; exit 1; }
for command in wrk curl python3; do command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 1; }; done
RUNS="${RUNS:-5}"
WARMUP="${WARMUP:-10}"
DURATION="${DURATION:-30}"
THREADS="${THREADS:-4}"
CONNECTIONS="${CONNECTIONS:-64}"
PORT="${PORT:-8080}"
export PORT
if curl --max-time 1 -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  echo "Port $PORT already serves /health. Stop existing processes before benchmarking." >&2
  exit 1
fi
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/results/$STAMP"
mkdir -p "$OUT"
GIT_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo none)"
{
  echo "timestamp_utc=$STAMP"
  echo "git_sha=$GIT_SHA"
  uname -a
  [[ ! -r /proc/cpuinfo ]] || grep -m1 -E '^model name\s*:' /proc/cpuinfo || true
  java -version 2>&1 | head -n 3 || true
  rustc --version 2>&1 || true
  cargo --version 2>&1 || true
  command -v gradle >/dev/null && gradle --version | grep -m1 Gradle || true
  echo "runs=$RUNS warmup=$WARMUP duration=$DURATION threads=$THREADS connections=$CONNECTIONS"
} > "$OUT/environment.txt"
current_pid=''
cleanup() {
  if [[ -n "$current_pid" ]]; then
    kill "$current_pid" 2>/dev/null || true
    wait "$current_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM
for variant in ktor-jvm ktor-native rust-axum; do
  for endpoint in /health /json /users/123; do
    for run in $(seq 1 "$RUNS"); do
      log="$OUT/${variant}_${endpoint//\//_}_${run}.wrk.txt"
      serverlog="$OUT/${variant}_${endpoint//\//_}_${run}.server.txt"
      "$ROOT/scripts/run-server.sh" "$variant" > "$serverlog" 2>&1 &
      current_pid=$!
      ready=0
      for _ in $(seq 1 100); do
        if ! kill -0 "$current_pid" 2>/dev/null; then
          echo "Server died: $variant — see $serverlog" >&2
          exit 1
        fi
        if curl --max-time 1 -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then ready=1; break; fi
        sleep .2
      done
      [[ "$ready" == 1 ]] || { echo "Server never became ready: $variant" >&2; exit 1; }
      "$ROOT/scripts/smoke.sh" "http://127.0.0.1:$PORT"
      wrk -t "$THREADS" -c "$CONNECTIONS" -d "${WARMUP}s" "http://127.0.0.1:$PORT$endpoint" > /dev/null
      read -r cpu_start _ < <(python3 "$ROOT/scripts/proc_metrics.py" "$current_pid")
      wrk --latency -t "$THREADS" -c "$CONNECTIONS" -d "${DURATION}s" "http://127.0.0.1:$PORT$endpoint" > "$log"
      read -r cpu_end peak_rss_kib < <(python3 "$ROOT/scripts/proc_metrics.py" "$current_pid")
      python3 "$ROOT/scripts/parse_wrk.py" \
        --log "$log" --csv "$OUT/results.csv" --variant "$variant" --endpoint "$endpoint" \
        --run "$run" --connections "$CONNECTIONS" --threads "$THREADS" \
        --duration "$DURATION" --warmup "$WARMUP" --cpu-start "$cpu_start" \
        --cpu-end "$cpu_end" --peak-rss-kib "$peak_rss_kib" --git-sha "$GIT_SHA"
      cleanup
      current_pid=''
      sleep 1
    done
  done
done
python3 "$ROOT/scripts/summarize.py" "$OUT/results.csv" > "$OUT/summary.md"
echo "Results: $OUT/results.csv (raw logs alongside)"
echo "Summary: $OUT/summary.md"
