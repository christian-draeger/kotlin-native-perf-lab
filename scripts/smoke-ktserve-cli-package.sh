#!/usr/bin/env bash
set -euo pipefail

archive="${1:?Usage: smoke-ktserve-cli-package.sh ARCHIVE}"
package="$(cd "$(dirname "$archive")" && pwd)"
archive="$(basename "$archive")"
executable="${archive%.tar.gz}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
smoke="$root/components/ktserve/experiments/cli/static"

if [[ "$(uname -s)" == Linux ]]; then
  (cd "$package" && sha256sum --strict -c "$archive.sha256" "$executable.sha256")
else
  (cd "$package" && shasum -a 256 -c "$archive.sha256" "$executable.sha256")
fi

unpacked="$(mktemp -d)"
pid=
cleanup() {
  if [[ -n "$pid" ]]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf "$unpacked"
}
trap cleanup EXIT

tar -xzf "$package/$archive" -C "$unpacked"
cd "$unpacked"
cmp "$root/LICENSE" LICENSE
cmp "$package/$executable" ktserve
install -m 0755 "$package/$executable" ktserve-standalone
python3 -B "$smoke/stdin_smoke.py" ./ktserve-standalone
export BIND_HOST=127.0.0.1
if [[ "$archive" == *-https.tar.gz ]]; then
  python3 "$smoke/https_smoke.py" ./ktserve
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -noenc -days 1 \
    -subj /CN=localhost -addext subjectAltName=DNS:localhost \
    -keyout localhost.key -out localhost.pem
  PORT=18443 ./ktserve serve ./public --tls-cert localhost.pem --tls-key localhost.key > startup.log 2>&1 &
  pid=$!
  curl_args=(--cacert localhost.pem --resolve localhost:18443:127.0.0.1 https://localhost:18443/api/status.json)
else
  python3 "$smoke/smoke.py" ./ktserve
  PORT=18081 ./ktserve serve ./public > startup.log 2>&1 &
  pid=$!
  curl_args=(http://127.0.0.1:18081/api/status.json)
fi
if ! curl -fsS --retry 8 --retry-connrefused --retry-max-time 10 "${curl_args[@]}" -o response.json; then
  cat startup.log >&2
  exit 1
fi
cmp public/api/status.json response.json
printf 'The bundled sample responded successfully from %s.\n' "$archive"
