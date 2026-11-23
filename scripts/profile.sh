#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
[[ "$(uname -s)" == Linux ]] || { echo 'Profiles require Linux perf and JDK Flight Recorder.' >&2; exit 1; }
export BIND_HOST=127.0.0.1 PORT="${PORT:-8080}"
[[ "$PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( PORT <= 65532 )) || {
  echo 'PORT must leave room for four consecutive TCP ports.' >&2
  exit 2
}
out="$(realpath -m "${1:?Usage: profile.sh OUTPUT_DIRECTORY}")"
mkdir "$out"
load_pid=''
cleanup() {
  if [[ -n "$load_pid" ]]; then
    kill "$load_pid" 2>/dev/null || true
    wait "$load_pid" 2>/dev/null || true
  fi
  stop_server
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

perf=("${PERF_BIN:-perf}")
if [[ "${PERF_SUDO:-0}" == 1 ]]; then perf=(sudo -n "${perf[@]}"); fi
load=(wrk -t 2 -c 16 --timeout 2s -s "$ROOT/scripts/wrk-stats.lua")
{
  echo 'Diagnostic CPU sampling: /users/123, local HTTP/1.1, 2 wrk threads, 16 connections.'
  echo 'Ten seconds of warm-up; about 15 seconds of profiling, one process per variant.'
  echo "git_sha=$(git -C "$ROOT" rev-parse HEAD)"
  echo "git_tree=$(git -C "$ROOT" rev-parse 'HEAD^{tree}')"
  printf 'kernel=%s\nmachine=%s\n' "$(uname -sr)" "$(uname -m)"
  cat /etc/os-release
  lscpu | awk '/^CPU\(s\):|^Vendor ID:|^Model name:/'
  taskset -pc "$$"
  java ${JAVA_OPTS:-} -XX:+PrintCommandLineFlags -version 2>&1
  rustc -Vv
  wrk_version="$(wrk -v 2>&1 || true)"
  printf '%s\n' "${wrk_version%%$'\n'*}"
  "${perf[@]}" --version
} > "$out/environment.txt"
(
  cd "$ROOT"
  sha256sum competitors/ktor/build/jvmDist/app.jar competitors/ktor/build/jvmDist/lib/*.jar \
    competitors/ktor/build/bin/native/releaseExecutable/ktor-baselines.kexe \
    competitors/rust-axum/target/release/axum-baseline \
    components/nativeserve/build/bin/native/releaseExecutable/nativeserve.kexe
) > "$out/binaries.sha256"

check_load() {
  if ! grep -Eq '^perflab requests=[1-9][0-9]* .*connect=0 read=0 write=0 timeout=0 http_errors=0$' "$1"; then
    cat "$1" >&2
    return 1
  fi
}
for variant in ktor-jvm ktor-native rust-axum kotlin-reactor; do
  base="http://$BIND_HOST:$PORT"
  url="$base/users/123"
  prefix="$out/$variant"
  printf '%s %s\n' "$variant" "$PORT" >> "$out/ports.txt"
  start_server "$variant" "$prefix.server.txt"
  "$ROOT/scripts/smoke.sh" "$base" > "$prefix.smoke.txt"
  "${load[@]}" -d 10s "$url" > "$prefix.warmup.txt"
  check_load "$prefix.warmup.txt"

  if [[ "$variant" == ktor-jvm ]]; then
    jcmd "$server_pid" JFR.start name=perflab settings=profile
    "${load[@]}" -d 15s "$url" > "$prefix.load.txt"
    jcmd "$server_pid" JFR.stop name=perflab filename="$prefix.jfr"
    events=jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GarbageCollection,jdk.Compilation
    jfr print --json --stack-depth 40 --events "$events" "$prefix.jfr" |
      gzip -n > "$prefix.jfr.json.gz"
  else
    "${load[@]}" -d 17s "$url" > "$prefix.load.txt" &
    load_pid=$!
    sleep 1
    "${perf[@]}" record -o "$prefix.perf.data" -F 99 -e cpu-clock \
      --call-graph dwarf,8192 -p "$server_pid" -- sleep 15
    wait "$load_pid"
    load_pid=''
    "${perf[@]}" report -i "$prefix.perf.data" --stdio --no-children \
      --sort dso,symbol --percent-limit 0.5 -g none > "$prefix.perf.txt"
    "${perf[@]}" script -i "$prefix.perf.data" | gzip -n > "$prefix.perf-stacks.txt.gz"
    if [[ "${PERF_SUDO:-0}" == 1 ]]; then
      sudo -n chown "$(id -u):$(id -g)" "$prefix.perf.data"
    fi
  fi
  check_load "$prefix.load.txt"
  stop_server
  PORT=$((PORT + 1))
done
