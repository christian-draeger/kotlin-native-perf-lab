#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${PREBUILT_KOTLIN_DIR:-}" ]]; then
  native_directory="$ROOT/competitors/ktor/build/bin/native/releaseExecutable"
  ktserve_directory="$ROOT/components/ktserve/build/bin/native/releaseExecutable"
  mkdir -p "$native_directory" "$ktserve_directory"
  install -m 755 "$PREBUILT_KOTLIN_DIR/ktor-baselines.kexe" "$native_directory/ktor-baselines.kexe"
  install -m 755 "$PREBUILT_KOTLIN_DIR/ktserve.kexe" "$ktserve_directory/ktserve.kexe"
  cp -R "$PREBUILT_KOTLIN_DIR/jvmDist" "$ROOT/competitors/ktor/build/"
else
  if [[ -x "$ROOT/competitors/ktor/gradlew" ]]; then
    gradle="$ROOT/competitors/ktor/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    gradle=gradle
  else
    gradle="$ROOT/components/ktserve/gradlew"
  fi
  "$gradle" -p "$ROOT/competitors/ktor" jvmDist linkReleaseExecutableNative
  "$gradle" -p "$ROOT/components/ktserve" linkReleaseExecutableNative
fi
cargo build --release --locked --manifest-path "$ROOT/competitors/rust-axum/Cargo.toml"
echo 'Built Ktor JVM, Ktor Native, Rust Axum and ktServe.'
