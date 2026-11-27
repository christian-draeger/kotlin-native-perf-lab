#!/usr/bin/env bash
set -euo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mirror=()
gradle_command="${KTSERVE_CI_GRADLE_COMMAND:-gradle}"

for attempt in 1 2 3; do
  if "$gradle_command" "${mirror[@]}" "$@" 2>&1 | tee "$log"; then
    exit 0
  fi
  if [ "$attempt" -eq 3 ] || ! grep -Fq 'Received status code 429 from server' "$log"; then
    exit 1
  fi
  echo 'Maven Central rate limited this build. Retrying with its Google Cloud mirror.'
  mirror=(--init-script "$script_dir/ci-maven-central-mirror.gradle")
  sleep "$((5 * attempt))"
done
