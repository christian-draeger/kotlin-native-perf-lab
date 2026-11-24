#!/usr/bin/env bash
set -euo pipefail

binary="${1:?Pass the native h2c executable}"
caddy="${2:-caddy}"
directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
stage="$(mktemp -d)"
server_pid=''
proxy_pid=''
cleanup() {
    if [[ -n "$proxy_pid" ]]; then
        kill "$proxy_pid" 2>/dev/null || true
        wait "$proxy_pid" 2>/dev/null || true
    fi
    if [[ -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
    rm -rf "$stage"
}
trap cleanup EXIT

export KTSERVE_H2C_PORT=18083 KTSERVE_TLS_PORT=18443
export KTSERVE_TLS_HOST=localhost KTSERVE_TLS_BIND=127.0.0.1
export KTSERVE_TLS_KEY="$stage/key.pem" KTSERVE_TLS_CERT="$stage/cert.pem"
export XDG_DATA_HOME="$stage/data" XDG_CONFIG_HOME="$stage/config"
umask 077
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 1 -subj '/CN=localhost' \
    -addext 'subjectAltName=DNS:localhost' -keyout "$KTSERVE_TLS_KEY" -out "$KTSERVE_TLS_CERT" 2>/dev/null

"$binary" "$KTSERVE_H2C_PORT" &
server_pid=$!
curl --http2-prior-knowledge -fsS --retry 8 --retry-connrefused --retry-max-time 10 \
    "http://127.0.0.1:$KTSERVE_H2C_PORT/health"

"$caddy" validate --config "$directory/Caddyfile" --adapter caddyfile
"$caddy" run --config "$directory/Caddyfile" --adapter caddyfile &
proxy_pid=$!

options=(--cacert "$KTSERVE_TLS_CERT" --resolve "localhost:$KTSERVE_TLS_PORT:127.0.0.1"
    -fsS --retry 8 --retry-connrefused --retry-max-time 10 -w $'\n%{http_version}')
base="https://localhost:$KTSERVE_TLS_PORT"
test "$(curl "${options[@]}" --http2 "$base/health")" = $'ok\n2'
test "$(curl "${options[@]}" --http1.1 "$base/health")" = $'ok\n1.1'
test "$(curl "${options[@]}" --http2 "$base/users/123")" = $'{"id":123,"name":"benchmark-user","active":true}\n2'
/usr/bin/python3 "$directory/../multiplex_smoke.py" "$KTSERVE_TLS_PORT" --ca "$KTSERVE_TLS_CERT" --shutdown-pid "$proxy_pid"

wait "$proxy_pid"
proxy_pid=''
kill -TERM "$server_pid"
wait "$server_pid"
server_pid=''
echo 'TLS ingress passed HTTP/2 ALPN, HTTP/1.1 fallback and shutdown'
