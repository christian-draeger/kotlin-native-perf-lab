#!/usr/bin/env bash
set -euo pipefail

binary="${1:?Pass the native h2c executable}"
directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
port="${2:-18082}"
base="http://127.0.0.1:$port"
"$binary" "$port" &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT

health="$(curl --http2-prior-knowledge -fsS --retry 8 --retry-connrefused --retry-max-time 10 \
    -w $'\n%{http_version}' "$base/health")"
test "$health" = $'ok\n2'
test "$(curl --http2-prior-knowledge -fsS "$base/json")" = '{"id":42,"name":"benchmark-user","active":true}'
test "$(curl --http2-prior-knowledge -fsS "$base/users/123")" = '{"id":123,"name":"benchmark-user","active":true}'
test "$(printf '%s' 'native-http2' | curl --http2-prior-knowledge -fsS --data-binary @- "$base/upload")" = 12
test "$(printf '%s' 'native-http2' | curl --http2-prior-knowledge -fsS -H 'Content-Length:' --data-binary @- "$base/upload")" = 12
curl --http2-prior-knowledge -fsS "$base/stream" | python3 -c 'import sys; assert sys.stdin.buffer.read() == b"x" * (80 * 1024)'
awk '/^(VmHWM|Threads):/ { print }' "/proc/$pid/status"
/usr/bin/python3 "$directory/multiplex_smoke.py" "$port" --shutdown-pid "$pid"
wait "$pid"
echo 'ktServe HTTP/2 prior-knowledge parity, streaming and shutdown passed'
