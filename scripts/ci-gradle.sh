#!/usr/bin/env bash
set -euo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in 1 2 3; do
  if gradle "$@" 2>&1 | tee "$log"; then
    exit 0
  fi
  if [ "$attempt" -eq 3 ] || ! grep -Fq 'Received status code 429 from server' "$log"; then
    exit 1
  fi
  echo 'Gradle dependency resolution was rate limited. Retrying.'
  sleep "$((5 * attempt))"
done
