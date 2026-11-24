#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
variant="${1:?Usage: run-server.sh VARIANT}"
case "$variant" in
  ktor-jvm)
    jar="$ROOT/competitors/ktor/build/jvmDist/app.jar"
    [[ -f "$jar" ]] || { echo 'Missing JVM build. Run scripts/build-all.sh' >&2; exit 1; }
    exec java ${JAVA_OPTS:-} -cp "$jar:$ROOT/competitors/ktor/build/jvmDist/lib/*" dev.perflab.MainJvmKt
    ;;
  ktor-native)
    bin="$ROOT/competitors/ktor/build/bin/native/releaseExecutable/ktor-baselines.kexe"
    [[ -x "$bin" ]] || { echo "Missing Kotlin/Native binary at $bin" >&2; exit 1; }
    exec "$bin"
    ;;
  rust-axum)
    bin="$ROOT/competitors/rust-axum/target/release/axum-baseline"
    [[ -x "$bin" ]] || { echo 'Missing Axum release binary. Run scripts/build-all.sh' >&2; exit 1; }
    exec "$bin"
    ;;
  nativeserve-reference)
    exec env NATIVESERVE_WORKERS=1 "${NATIVESERVE_REFERENCE_BINARY:?Missing NativeServe reference binary}"
    ;;
  nativeserve-epoll|nativeserve-io-uring)
    backend=epoll
    if [[ "$variant" == nativeserve-io-uring ]]; then backend=io_uring; fi
    exec env NATIVESERVE_BACKEND="$backend" "${NATIVESERVE_IO_BINARY:?Missing experimental backend binary}" \
      "${PORT:-8080}" "${BIND_HOST:-127.0.0.1}"
    ;;
  nativeserve|kotlin-reactor|nativeserve-workers-2|nativeserve-workers-2-reuseport|nativeserve-gathered)
    bin="$ROOT/components/nativeserve/build/bin/native/releaseExecutable/nativeserve.kexe"
    [[ -x "$bin" ]] || { echo 'Missing NativeServe release binary. Run scripts/build-all.sh' >&2; exit 1; }
    if [[ "$variant" == nativeserve-workers-2* ]]; then
      export NATIVESERVE_WORKERS=2
      export NATIVESERVE_WORKER_LISTENER=shared
      if [[ "$variant" == nativeserve-workers-2-reuseport ]]; then
        export NATIVESERVE_WORKER_LISTENER=reuseport
      fi
    elif [[ "${BENCHMARK_SUITE:-}" == workers ]]; then
      export NATIVESERVE_WORKERS=1
    fi
    if [[ "$variant" == nativeserve-gathered ]]; then
      export NATIVESERVE_WORKERS=1 NATIVESERVE_RESPONSE_WRITES=gathered
    elif [[ "${BENCHMARK_SUITE:-}" == responses ]]; then
      export NATIVESERVE_WORKERS=1 NATIVESERVE_RESPONSE_WRITES=contiguous
    fi
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
  fastapi|django)
    app="$ROOT/competitors/$variant"
    exec "$app/.venv/bin/python" -m uvicorn server:app --app-dir "$app" --host "${BIND_HOST:-127.0.0.1}" \
      --port "${PORT:-8080}" --workers 1 --loop uvloop --http httptools --no-access-log
    ;;
  flask)
    app="$ROOT/competitors/flask"
    cd "$app"
    exec "$app/.venv/bin/waitress-serve" --listen="${BIND_HOST:-127.0.0.1}:${PORT:-8080}" --threads=4 server:app
    ;;
  spring-boot|spring-webflux)
    exec java ${JAVA_OPTS:-} -jar "$ROOT/competitors/$variant/target/$variant.jar" \
      --server.address="${BIND_HOST:-127.0.0.1}" --server.port="${PORT:-8080}" --spring.main.banner-mode=off
    ;;
  aspnet-core)
    exec dotnet "$ROOT/competitors/aspnet-core/target/publish/AspNetCore.dll"
    ;;
  quarkus)
    exec java ${JAVA_OPTS:-} -Dquarkus.http.host="${BIND_HOST:-127.0.0.1}" -Dquarkus.http.port="${PORT:-8080}" \
      -jar "$ROOT/competitors/kotlin-jvm/quarkus/target/quarkus-app/quarkus-run.jar"
    ;;
  vertx|micronaut|http4k|javalin)
    app="$ROOT/competitors/kotlin-jvm/$variant/target"
    exec java ${JAVA_OPTS:-} -cp "$app/app.jar:$app/lib/*" dev.perflab.ApplicationKt
    ;;
  *) echo "Unknown variant: $variant" >&2; exit 2 ;;
esac
