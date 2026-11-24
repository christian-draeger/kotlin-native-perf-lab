#!/usr/bin/env bash
set -euo pipefail

binary="${1:?Pass the native h2c executable}"
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
kill -TERM "$pid"
wait "$pid"
echo 'NativeServe HTTP/2 prior-knowledge parity and shutdown passed'
