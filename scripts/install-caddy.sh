#!/usr/bin/env bash
set -euo pipefail

destination="${1:?Provide a destination directory for Caddy}"
case "$(uname -m)" in
  x86_64) arch=amd64; digest=5b8cb7faf8726a2028ad96a66e54e6e81e8f361bc6175e5d22bbd997cc5177fb ;;
  aarch64) arch=arm64; digest=5e377cff994fc9362a6fcf695c53f226cfca3a3ef85bd3a3687cf252d6310165 ;;
  *) echo 'Install Caddy on a supported Linux architecture.' >&2; exit 2 ;;
esac
version=2.11.9
asset="caddy_${version}_linux_$arch.tar.gz"
mkdir -p "$destination"
curl -fsSL --retry 3 "https://github.com/caddyserver/caddy/releases/download/v$version/$asset" -o "$destination/$asset"
printf '%s  %s\n' "$digest" "$destination/$asset" | sha256sum --strict -c -
tar -xzf "$destination/$asset" -C "$destination" caddy
