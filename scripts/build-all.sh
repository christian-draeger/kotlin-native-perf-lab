#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${PREBUILT_KTOR_DIR:-}" ]]; then
  native_directory="$ROOT/ktor/build/bin/native/releaseExecutable"
  mkdir -p "$native_directory"
  install -m 755 "$PREBUILT_KTOR_DIR/ktor-baselines.kexe" "$native_directory/ktor-baselines.kexe"
  cp -R "$PREBUILT_KTOR_DIR/jvmDist" "$ROOT/ktor/build/"
elif [[ -x "$ROOT/ktor/gradlew" ]]; then
  "$ROOT/ktor/gradlew" -p "$ROOT/ktor" jvmDist linkReleaseExecutableNative
elif command -v gradle >/dev/null 2>&1; then
  gradle -p "$ROOT/ktor" jvmDist linkReleaseExecutableNative
else
  echo 'Gradle not found. Install Gradle 9.7 or generate the wrapper in ktor/.' >&2
  exit 1
fi
cargo build --release --locked --manifest-path "$ROOT/rust-axum/Cargo.toml"
echo 'Built Ktor JVM, Ktor Native and Rust Axum.'
