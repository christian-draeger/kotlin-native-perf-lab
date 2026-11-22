#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

for script in scripts/*.sh; do
  bash -n "$script"
done
node --check dashboard/app.js
python3 -m unittest discover -s scripts/tests

output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
python3 scripts/build-dashboard.py "$output"
test -s "$output/index.html"
test -s "$output/data.js"
