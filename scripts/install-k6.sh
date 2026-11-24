#!/usr/bin/env bash
set -euo pipefail

destination="${1:?Provide a destination directory for k6}"
case "$(uname -m)" in
  x86_64) arch=amd64; digest=5a5ecf23529ccf93135bc7e5b0c1cd5126ecf5f2672e22eed1556a0ab74abb87 ;;
  aarch64) arch=arm64; digest=7bb415f59d81ad649a86f6da6b3e1bd2e4e64e84909deef13e9bca70608abccb ;;
  *) echo 'Install k6 on a supported Linux architecture.' >&2; exit 2 ;;
esac
asset="k6-v2.4.0-linux-$arch.tar.gz"
mkdir -p "$destination"
curl -fsSL --retry 3 "https://github.com/grafana/k6/releases/download/v2.4.0/$asset" -o "$destination/$asset"
printf '%s  %s\n' "$digest" "$destination/$asset" | sha256sum --strict -c -
tar -xzf "$destination/$asset" --strip-components=1 -C "$destination" "k6-v2.4.0-linux-$arch/k6"
