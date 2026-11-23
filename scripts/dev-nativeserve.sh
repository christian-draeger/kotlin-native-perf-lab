#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="$root/components/nativeserve"
port="${PORT:-8080}"
image="docker.io/library/ubuntu@sha256:cd04f18ea6639206fcecf4c148bde909cd08098e1d0569117c2af59242059558"

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo 'This development command requires Apple Silicon macOS.' >&2
  exit 1
fi
if [[ ! "$port" =~ ^[1-9][0-9]{0,4}$ ]] || (( port > 65535 )); then
  echo 'PORT must be a decimal TCP port between 1 and 65535.' >&2
  exit 2
fi
if [[ "$(docker info --format '{{.OSType}}')" != linux ]]; then
  echo 'Start a local Docker-compatible Linux runtime before running this command.' >&2
  exit 1
fi

"$project/gradlew" -p "$project" -Pbenchmark.targetArch=aarch64 linkReleaseExecutableNative
binary="$project/build/bin/native/releaseExecutable/nativeserve.kexe"

echo "Starting NativeServe at http://127.0.0.1:$port"
exec docker run --rm --platform linux/arm64 \
  --publish "127.0.0.1:$port:8080" \
  --mount "type=bind,source=$binary,target=/usr/local/bin/nativeserve,readonly" \
  --env BIND_HOST=0.0.0.0 --env PORT=8080 \
  "$image" /usr/local/bin/nativeserve
