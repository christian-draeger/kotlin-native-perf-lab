#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${PREBUILT_KOTLIN_DIR:-}" ]]; then
  native_directory="$ROOT/ktor/build/bin/native/releaseExecutable"
  reactor_directory="$ROOT/kotlin-reactor/build/bin/native/releaseExecutable"
  mkdir -p "$native_directory" "$reactor_directory"
  install -m 755 "$PREBUILT_KOTLIN_DIR/ktor-baselines.kexe" "$native_directory/ktor-baselines.kexe"
  install -m 755 "$PREBUILT_KOTLIN_DIR/nativeserve.kexe" "$reactor_directory/nativeserve.kexe"
  cp -R "$PREBUILT_KOTLIN_DIR/jvmDist" "$ROOT/ktor/build/"
else
  if [[ -x "$ROOT/ktor/gradlew" ]]; then
    gradle="$ROOT/ktor/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    gradle=gradle
  else
    gradle="$ROOT/kotlin-reactor/gradlew"
  fi
  "$gradle" -p "$ROOT/ktor" jvmDist linkReleaseExecutableNative
  "$gradle" -p "$ROOT/kotlin-reactor" linkReleaseExecutableNative
fi
cargo build --release --locked --manifest-path "$ROOT/rust-axum/Cargo.toml"
echo 'Built Ktor JVM, Ktor Native, Rust Axum and NativeServe.'
