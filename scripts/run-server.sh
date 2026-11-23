#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
variant="${1:?Usage: run-server.sh VARIANT}"
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
  nativeserve|kotlin-reactor)
    bin="$ROOT/kotlin-reactor/build/bin/native/releaseExecutable/nativeserve.kexe"
    [[ -x "$bin" ]] || { echo 'Missing NativeServe release binary. Run scripts/build-all.sh' >&2; exit 1; }
    exec "$bin"
    ;;
  go-http)
    exec "$ROOT/competitors/go-http/build/go-http"
    ;;
  hono-node)
    exec node "$ROOT/competitors/hono-node/server.js"
    ;;
  elysia-bun)
    exec bun "$ROOT/competitors/elysia-bun/server.js"
    ;;
  fastapi)
    app="$ROOT/competitors/fastapi"
    exec "$app/.venv/bin/python" -m uvicorn server:app --app-dir "$app" --host 127.0.0.1 \
      --port "${PORT:-8080}" --workers 1 --loop uvloop --http httptools --no-access-log
    ;;
  spring-boot)
    exec java ${JAVA_OPTS:-} -jar "$ROOT/competitors/spring-boot/target/spring-boot.jar" \
      --server.address=127.0.0.1 --server.port="${PORT:-8080}" --spring.main.banner-mode=off
    ;;
  *) echo "Unknown variant: $variant" >&2; exit 2 ;;
esac
