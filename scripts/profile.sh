#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
[[ "$(uname -s)" == Linux ]] || { echo 'Profiles require Linux perf and JDK Flight Recorder.' >&2; exit 1; }
export PORT="${PORT:-8080}"
out="$(realpath -m "${1:?Usage: profile.sh OUTPUT_DIRECTORY}")"
mkdir "$out"
scratch="$(mktemp -d)"
load_pid=''
cleanup() {
  if [[ -n "$load_pid" ]]; then
    kill "$load_pid" 2>/dev/null || true
    wait "$load_pid" 2>/dev/null || true
  fi
  stop_server
  rm -rf "$scratch"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

perf=("${PERF_BIN:-perf}")
if [[ "${PERF_SUDO:-0}" == 1 ]]; then perf=(sudo -n "${perf[@]}"); fi
url="http://127.0.0.1:$PORT/users/123"
load=(wrk -t 2 -c 16 --timeout 2s -s "$ROOT/scripts/wrk-stats.lua")
{
  echo 'Diagnostic CPU sampling: /users/123, local HTTP/1.1, 2 wrk threads, 16 connections.'
  echo 'Ten seconds of warm-up; about 15 seconds of profiling, one process per variant.'
  echo "git_sha=$(git -C "$ROOT" rev-parse HEAD)"
  printf 'kernel=%s\nmachine=%s\n' "$(uname -sr)" "$(uname -m)"
  lscpu | awk '/^CPU\(s\):|^Vendor ID:|^Model name:/'
  java -version 2>&1
  "${perf[@]}" --version
} > "$out/environment.txt"

check_load() {
  if ! grep -Eq '^perflab requests=[1-9][0-9]* .*connect=0 read=0 write=0 timeout=0 http_errors=0$' "$1"; then
    cat "$1" >&2
    return 1
  fi
}
for variant in ktor-jvm ktor-native rust-axum kotlin-reactor; do
  start_server "$variant" "$scratch/server.txt"
  "$ROOT/scripts/smoke.sh" "http://127.0.0.1:$PORT"
  "${load[@]}" -d 10s "$url" > "$scratch/warmup.txt"
  check_load "$scratch/warmup.txt"

  if [[ "$variant" == ktor-jvm ]]; then
    jcmd "$server_pid" JFR.start name=perflab settings=profile
    "${load[@]}" -d 15s "$url" > "$scratch/load.txt"
    jcmd "$server_pid" JFR.stop name=perflab filename="$scratch/jvm.jfr"
    events=jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GarbageCollection,jdk.Compilation
    jfr print --json --stack-depth 40 --events "$events" "$scratch/jvm.jfr" |
      gzip -n > "$out/ktor-jvm.jfr.json.gz"
  else
    "${load[@]}" -d 17s "$url" > "$scratch/load.txt" &
    load_pid=$!
    sleep 1
    "${perf[@]}" record -o "$scratch/$variant.data" -F 99 -e cpu-clock \
      --call-graph dwarf,8192 -p "$server_pid" -- sleep 15
    wait "$load_pid"
    load_pid=''
    "${perf[@]}" report -i "$scratch/$variant.data" --stdio --no-children \
      --sort dso,symbol --percent-limit 0.5 -g none > "$out/$variant.perf.txt"
    "${perf[@]}" script -i "$scratch/$variant.data" | gzip -n > "$out/$variant.perf-stacks.txt.gz"
  fi
  check_load "$scratch/load.txt"
  stop_server
done
