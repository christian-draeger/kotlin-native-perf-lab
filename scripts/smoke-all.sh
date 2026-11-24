#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/server-process.sh"
source "$ROOT/scripts/benchmark-variants.sh"
ports=()
if [[ -z "${PORT:-}" ]]; then
  # A previous server's HTTP socket can remain in TIME_WAIT after shutdown.
  assigned="$(python3 - "${#variants[@]}" "${BIND_HOST:-127.0.0.1}" <<'PY'
from contextlib import ExitStack
import socket
import sys

with ExitStack() as stack:
    ports = []
    for _ in range(int(sys.argv[1])):
        listener = stack.enter_context(socket.socket())
        listener.bind((sys.argv[2], 0))
        ports.append(str(listener.getsockname()[1]))
    print(' '.join(ports))
PY
)"
  read -r -a ports <<< "$assigned"
fi
export PORT
logs="$(mktemp -d)"
trap 'stop_server; rm -rf "$logs"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for index in "${!variants[@]}"; do
  variant="${variants[index]}"
  PORT="${ports[index]:-$PORT}"
  echo "=== $variant ==="
  log="$logs/$variant.txt"
  start_server "$variant" "$log"
  if ! "$ROOT/scripts/smoke.sh" "http://${BIND_HOST:-127.0.0.1}:$PORT"; then
    cat "$log" >&2
    exit 1
  fi
  if [[ "$variant" == kotlin-reactor && "${BENCHMARK_SUITE:-core}" != application ]]; then
    python3 "$ROOT/scripts/tests/reactor_http_smoke.py" "$PORT"
    python3 "$ROOT/scripts/tests/reactor_shutdown_smoke.py" "$PORT" "$server_pid"
    wait "$server_pid"
    server_pid=''
    start_server "$variant" "$logs/reactor-rebind.txt"
  fi
  stop_server
done
