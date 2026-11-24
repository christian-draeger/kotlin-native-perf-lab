#!/usr/bin/env bash
set -euo pipefail

server=$1
fixture=$2
uname -smr
status=0
"$server" --capabilities || status=$?
if [ "$status" -eq 77 ]; then
  echo "io_uring unavailable on this host. This run has no io_uring socket evidence." >&2
  exit 77
fi
if [ "$status" -ne 0 ]; then exit "$status"; fi

export NATIVESERVE_BACKEND=io_uring
python3 scripts/tests/reactor_stress.py "$fixture"
