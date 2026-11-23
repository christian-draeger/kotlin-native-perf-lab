#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../competitors" && pwd)"

(
  cd "$ROOT/go-http"
  mkdir -p build
  CGO_ENABLED=0 go build -trimpath -o build/go-http .
)
(
  cd "$ROOT/hono-node"
  npm ci --ignore-scripts --no-audit --no-fund
)
(
  cd "$ROOT/elysia-bun"
  bun install --frozen-lockfile --ignore-scripts
)
(
  cd "$ROOT/fastapi"
  uv sync --locked --no-dev
)
mvn -B -ntp -f "$ROOT/spring-boot/pom.xml" package
mvn -B -ntp -f "$ROOT/kotlin-jvm/pom.xml" package
(
  cd "$ROOT/aspnet-core"
  dotnet publish -c Release -o target/publish
)
