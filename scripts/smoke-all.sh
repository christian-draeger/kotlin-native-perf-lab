#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
source "$ROOT/scripts/benchmark-variants.sh"
export PORT="${PORT:-8080}"
logs="$(mktemp -d)"
trap 'stop_server; rm -rf "$logs"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for variant in "${variants[@]}"; do
  echo "=== $variant ==="
  log="$logs/$variant.txt"
  start_server "$variant" "$log"
  if ! "$ROOT/scripts/smoke.sh" "http://${BIND_HOST:-127.0.0.1}:$PORT"; then
    cat "$log" >&2
    exit 1
  fi
  if [[ "$variant" == kotlin-reactor ]]; then
    python3 "$ROOT/scripts/tests/reactor_http_smoke.py" "$PORT"
    python3 "$ROOT/scripts/tests/reactor_shutdown_smoke.py" "$PORT" "$server_pid"
    wait "$server_pid"
    server_pid=''
    start_server "$variant" "$logs/reactor-rebind.txt"
  fi
  stop_server
done
