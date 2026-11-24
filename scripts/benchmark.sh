#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
source "$ROOT/scripts/benchmark-load.sh"
source "$ROOT/scripts/benchmark-variants.sh"
[[ "$(uname -s)" == Linux ]] || { echo 'This /proc benchmark runs on Linux.' >&2; exit 1; }
for command in curl python3 git taskset; do
  command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 1; }
done
RUNS="${RUNS:-5}"
WARMUP="${WARMUP:-10}"
WARMUP_WINDOWS="${WARMUP_WINDOWS:-1}"
DURATION="${DURATION:-30}"
THREADS="${THREADS:-4}"
CONNECTIONS="${CONNECTIONS:-64}"
for value in "$RUNS" "$WARMUP" "$WARMUP_WINDOWS" "$DURATION" "$THREADS" "$CONNECTIONS"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo 'Run parameters must be positive integers.' >&2; exit 2; }
done
(( WARMUP_WINDOWS == 1 || WARMUP_WINDOWS >= 3 )) || { echo 'WARMUP_WINDOWS must be 1 or at least 3.' >&2; exit 2; }
if (( WARMUP_WINDOWS > 1 )); then
  command -v jstat >/dev/null || { echo 'Install JDK 21 jstat for JVM warm-up checks.' >&2; exit 1; }
fi
(( CONNECTIONS % THREADS == 0 )) || { echo 'CONNECTIONS must be a multiple of THREADS.' >&2; exit 2; }
suite="${BENCHMARK_SUITE:-core}"
if [[ "$suite" == workers ]]; then
  [[ "${NATIVESERVE_REFERENCE_BINARY:-}" == /* && -x "$NATIVESERVE_REFERENCE_BINARY" ]] || {
    echo 'NATIVESERVE_REFERENCE_BINARY must be an absolute path to an executable release binary.' >&2; exit 2;
  }
  [[ "${NATIVESERVE_REFERENCE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || {
    echo 'NATIVESERVE_REFERENCE_SHA must identify the reference source commit.' >&2; exit 2;
  }
fi
if [[ "$suite" == io-backends ]]; then
  [[ "${NATIVESERVE_IO_BINARY:-}" == /* && -x "$NATIVESERVE_IO_BINARY" ]] || {
    echo 'NATIVESERVE_IO_BINARY must be an absolute executable path.' >&2; exit 2;
  }
fi
if [[ "$suite" == workers || "$suite" == io-backends ]]; then
  if [[ -z "${LOAD_SSH:-}" ]]; then
    : "${SERVER_CPUS:?Specify server CPU affinity or use a separate LOAD_SSH host}"
    : "${LOAD_CPUS:?Specify load CPU affinity or use a separate LOAD_SSH host}"
    server_affinity="$(taskset -c "$SERVER_CPUS" python3 -c 'import os; print(" ".join(map(str, sorted(os.sched_getaffinity(0)))))')"
    load_affinity="$(taskset -c "$LOAD_CPUS" python3 -c 'import os; print(" ".join(map(str, sorted(os.sched_getaffinity(0)))))')"
    python3 - "$server_affinity" "$load_affinity" <<'PY'
import sys
if set(sys.argv[1].split()) & set(sys.argv[2].split()):
    sys.exit("Server and load CPU affinity sets must be disjoint.")
PY
  fi
fi
endpoints=(/health /json /users/123)
if [[ "$suite" == application ]]; then endpoints=(/catalog/123); fi
if [[ -n "${BENCHMARK_ENDPOINT:-}" ]]; then
  if ! printf '%s\n' "${endpoints[@]}" | grep -Fxq -- "$BENCHMARK_ENDPOINT"; then
    printf 'Invalid BENCHMARK_ENDPOINT for %s. Allowed: %s\n' "$suite" "${endpoints[*]}" >&2
    exit 2
  fi
  endpoints=("$BENCHMARK_ENDPOINT")
fi
export BIND_HOST="${BIND_HOST:-127.0.0.1}"
export NO_PROXY="${NO_PROXY:-${no_proxy:-}},$BIND_HOST"
export no_proxy="$NO_PROXY"
# Allocate distinct ports before any trial. After load, accepted connections can
# remain in TIME_WAIT and prevent a different runtime from binding the same port.
ports="$(python3 - "$(( RUNS * ${#variants[@]} * ${#endpoints[@]} ))" "${PORT:-0}" "$BIND_HOST" "${LOAD_SSH:-}" <<'PY'
import contextlib
import ipaddress
import socket
import sys

count, start = map(int, sys.argv[1:3])
host = ipaddress.IPv4Address(sys.argv[3])
if host.is_unspecified or host.is_multicast or (sys.argv[4] and host.is_loopback):
    sys.exit("BIND_HOST must be this server's IPv4 interface address (non-loopback for LOAD_SSH).")
if not 0 <= start <= 65535 or start and start + count - 1 > 65535:
    sys.exit("PORT must leave enough room for one port per trial.")
with contextlib.ExitStack() as sockets:
    for index in range(count):
        probe = sockets.enter_context(socket.socket())
        probe.bind((str(host), start + index if start else 0))
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
trap 'stop_server; cleanup_load' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
prepare_load
GIT_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unavailable)"
(
  echo "timestamp_utc=$STAMP"
  echo "git_sha=$GIT_SHA"
  if [[ "$GIT_SHA" != unavailable ]]; then
    printf 'git_subject=%s\n' "$(git -C "$ROOT" show -s --format=%s "$GIT_SHA")"
    if [[ -z "$(git -C "$ROOT" status --porcelain)" ]]; then
      echo 'git_worktree=clean'
    else
      echo 'git_worktree=dirty'
    fi
  fi
  # Keep custom labels on one metadata line.
  if [[ -n "${BENCHMARK_LABEL:-}" ]]; then
    label="${BENCHMARK_LABEL//$'\n'/ }"
    label="${label//$'\r'/ }"
    printf 'benchmark_label=%s\n' "${label:0:120}"
  fi
  printf 'kernel=%s\n' "$(uname -sr)"
  printf 'machine=%s\n' "$(uname -m)"
  cat /etc/os-release
  lscpu
  taskset -pc "$$"
  awk '/^MemTotal:/ {print}' /proc/meminfo
  for field in cpu.max cpuset.cpus.effective memory.max; do
    if [[ -r "/sys/fs/cgroup/$field" ]]; then
      printf '%s=%s\n' "$field" "$(cat "/sys/fs/cgroup/$field")"
    fi
  done
  java ${JAVA_OPTS:-} -XX:+PrintCommandLineFlags -version 2>&1
  python3 --version
  cd "$ROOT"
  if [[ "$suite" == io-backends ]]; then
    printf 'kotlin_native=2.4.21\nliburing=%s\n' "${LIBURING_VERSION:-unknown}"
    sha256sum "$NATIVESERVE_IO_BINARY"
    printf 'server_affinity=%s\nload_affinity=%s\n' "${server_affinity:-${SERVER_CPUS:-all}}" "${load_affinity:-remote}"
    "$NATIVESERVE_IO_BINARY" --capabilities
  elif [[ "$suite" != competitors ]]; then
    rustc -Vv
    cargo --version
    if [[ -x competitors/ktor/gradlew ]]; then
      competitors/ktor/gradlew --version
    else
      gradle --version
    fi
    sha256sum competitors/ktor/build/jvmDist/app.jar competitors/ktor/build/jvmDist/lib/*.jar \
      competitors/ktor/build/bin/native/releaseExecutable/*.kexe competitors/rust-axum/target/release/axum-baseline \
      components/nativeserve/build/bin/native/releaseExecutable/nativeserve.kexe
  fi
  if [[ "$suite" == workers ]]; then
    printf 'nativeserve_reference_sha=%s\n' "$NATIVESERVE_REFERENCE_SHA"
    sha256sum "$NATIVESERVE_REFERENCE_BINARY"
    printf 'server_affinity=%s\nload_affinity=%s\n' "${server_affinity:-${SERVER_CPUS:-all}}" "${load_affinity:-remote}"
  fi
  if [[ "$suite" == expanded || "$suite" == competitors ]]; then
    go version competitors/go-http/build/go-http
    printf 'node=%s\nbun=%s\n' "$(node --version)" "$(bun --version)"
    uv --version
    for variant in fastapi flask django; do
      printf 'python_environment=%s\n' "$variant"
      "competitors/$variant/.venv/bin/python" --version
      uv pip freeze --python "competitors/$variant/.venv/bin/python"
    done
    mvn -B --version
    (cd competitors/aspnet-core && dotnet --info)
    sha256sum competitors/go-http/build/go-http competitors/spring-boot/target/spring-boot.jar \
      competitors/spring-webflux/target/spring-webflux.jar \
      competitors/hono-node/{package-lock.json,server.js} competitors/elysia-bun/{bun.lock,server.js} \
      competitors/{fastapi,flask,django}/{uv.lock,server.py} competitors/django/settings.py \
      competitors/aspnet-core/target/publish/AspNetCore.dll
    find competitors/kotlin-jvm/*/target -name '*.jar' -print0 | sort -z | xargs -0 sha256sum
  fi
  printf 'variants=%s\n' "${variants[*]}"
  printf 'benchmark_suite=%s\n' "${BENCHMARK_SUITE:-core}"
  if [[ "$suite" == workers ]]; then printf 'compare_worker_listeners=%s\n' "${COMPARE_WORKER_LISTENERS:-false}"; fi
  printf 'benchmark_endpoint=%s\n' "${BENCHMARK_ENDPOINT:-}"
  echo "runs=$RUNS warmup_s=$WARMUP target_duration_s=$DURATION threads=$THREADS connections=$CONNECTIONS timeout_s=2"
  echo "max_warmup_windows=$WARMUP_WINDOWS"
  echo 'port_strategy=distinct TCP port per trial'
  load_environment
) > "$OUT/environment.txt"

wrk_args=(--latency -t "$THREADS" -c "$CONNECTIONS" --timeout 2s)

warm_server() {
  local prefix="$1" variant="$2" url="$3" window log verdict
  local -a logs=() compiler_logs=() compiler_args=()
  warmup_status=unchecked
  warmup_seconds="$WARMUP"
  if (( WARMUP_WINDOWS == 1 )); then
    run_load "${wrk_args[@]}" -d "${WARMUP}s" "$url" > "$prefix.warmup.wrk.txt" 2>&1
    return
  fi

  warmup_status=unstable
  case "$variant" in
    ktor-jvm|spring-boot|spring-webflux|quarkus|vertx|micronaut|http4k|javalin)
      local snapshot="$prefix.warmup.0.jstat.txt"
      LC_ALL=C jstat -compiler -t "$server_pid" > "$snapshot" 2>&1
      compiler_logs+=("$snapshot")
      ;;
  esac
  for ((window = 1; window <= WARMUP_WINDOWS; window++)); do
    log="$prefix.warmup.$window.wrk.txt"
    run_load "${wrk_args[@]}" -d "${WARMUP}s" "$url" > "$log" 2>&1
    logs+=("$log")
    warmup_seconds=$((window * WARMUP))
    if (( ${#compiler_logs[@]} > 0 )); then
      snapshot="$prefix.warmup.$window.jstat.txt"
      LC_ALL=C jstat -compiler -t "$server_pid" > "$snapshot" 2>&1
      compiler_logs+=("$snapshot")
    fi
    if (( window < 3 )); then continue; fi
    if (( ${#compiler_logs[@]} > 0 )); then compiler_args=(--compiler "${compiler_logs[@]: -4}"); fi
    verdict="$(python3 "$ROOT/scripts/warmup.py" "${logs[@]: -3}" "${compiler_args[@]}")"
    printf 'window=%s %s\n' "$window" "$verdict" >> "$prefix.warmup.stability.txt"
    if [[ "${verdict%% *}" == stable ]]; then
      warmup_status=stable
      break
    fi
  done
  cp "$log" "$prefix.warmup.wrk.txt"
}

for ((run = 1; run <= RUNS; run++)); do
  for endpoint in "${endpoints[@]}"; do
    for ((offset = 0; offset < ${#variants[@]}; offset++)); do
      variant="${variants[(run - 1 + offset) % ${#variants[@]}]}"
      prefix="$OUT/${variant}_${endpoint//\//_}_$run"
      export PORT="${trial_ports[trial_index]}"
      trial_index=$((trial_index + 1))
      base="http://$BIND_HOST:$PORT"
      printf '%s %s %s %s\n' "$run" "$endpoint" "$variant" "$PORT" >> "$OUT/ports.txt"
      start_server "$variant" "$prefix.server.txt"
      "$ROOT/scripts/smoke.sh" "$base" > "$prefix.smoke.txt"
      warm_server "$prefix" "$variant" "$base$endpoint"
      read -r cpu_start _ _ < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
      run_load "${wrk_args[@]}" -d "${DURATION}s" "$base$endpoint" > "$prefix.wrk.txt" 2>&1
      read -r cpu_end peak_rss_kib rss_kib < <(python3 "$ROOT/scripts/proc_metrics.py" "$server_pid")
      variant_sha="$GIT_SHA"
      if [[ "$variant" == nativeserve-reference ]]; then variant_sha="$NATIVESERVE_REFERENCE_SHA"; fi
      python3 "$ROOT/scripts/parse_wrk.py" \
        --log "$prefix.wrk.txt" --warmup-log "$prefix.warmup.wrk.txt" --csv "$OUT/results.csv" \
        --variant "$variant" --endpoint "$endpoint" --run "$run" --connections "$CONNECTIONS" \
        --threads "$THREADS" --duration "$DURATION" --warmup "$warmup_seconds" \
        --warmup-stability "$warmup_status" --cpu-start "$cpu_start" \
        --cpu-end "$cpu_end" --peak-rss-kib "$peak_rss_kib" --rss-kib "$rss_kib" --git-sha "$variant_sha"
      stop_server
    done
  done
done
python3 "$ROOT/scripts/summarize.py" "$OUT/results.csv" > "$OUT/summary.md"
echo "Summary: $OUT/summary.md"
