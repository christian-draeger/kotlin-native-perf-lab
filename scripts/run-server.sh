#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
variant="${1:?Usage: run-server.sh ktor-jvm|ktor-native|rust-axum|kotlin-reactor}"
case "$variant" in
  ktor-jvm)
    jar="$ROOT/ktor/build/jvmDist/app.jar"
    [[ -f "$jar" ]] || { echo 'Missing JVM build. Run scripts/build-all.sh' >&2; exit 1; }
    exec java ${JAVA_OPTS:-} -cp "$jar:$ROOT/ktor/build/jvmDist/lib/*" dev.perflab.MainJvmKt
    ;;
  ktor-native)
    bin="$ROOT/ktor/build/bin/native/releaseExecutable/ktor-baselines.kexe"
    [[ -x "$bin" ]] || { echo "Missing Kotlin/Native binary at $bin" >&2; exit 1; }
    exec "$bin"
    ;;
  rust-axum)
    bin="$ROOT/rust-axum/target/release/axum-baseline"
    [[ -x "$bin" ]] || { echo 'Missing Axum release binary. Run scripts/build-all.sh' >&2; exit 1; }
    exec "$bin"
    ;;
  kotlin-reactor)
    bin="$ROOT/kotlin-reactor/build/bin/native/releaseExecutable/kotlin-reactor.kexe"
    [[ -x "$bin" ]] || { echo 'Missing Kotlin Reactor release binary. Run scripts/build-all.sh' >&2; exit 1; }
    exec "$bin"
    ;;
  *) echo "Unknown variant: $variant" >&2; exit 2 ;;
esac
