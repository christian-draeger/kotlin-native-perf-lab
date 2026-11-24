#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../competitors" && pwd)"

maven_settings=''
maven_log=''
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
  maven_log="$(mktemp)"
  trap 'rm -f "$maven_log"' EXIT
fi

build_maven() {
  local pom="$1"
  if [[ -n "$maven_settings" ]]; then
    mvn -B -ntp --settings "$maven_settings" -f "$pom" package
    return
  fi
  if [[ -z "$maven_log" ]]; then
    mvn -B -ntp -f "$pom" package
    return
  fi

  if mvn -B -ntp -f "$pom" package 2>&1 | tee "$maven_log"; then return; fi
  grep -Eq '(\[ERROR\]|\[FATAL\]).*https://repo\.maven\.apache\.org/maven2.*(HTTP Status|status code): 429([^0-9]|$)' "$maven_log" ||
    return 1
  echo 'Maven Central rate limited this build. Retrying with its Google Cloud mirror.'
  maven_settings="$ROOT/../scripts/ci-maven-central-mirror.xml"
  mvn -B -ntp --settings "$maven_settings" -f "$pom" package
}

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
for variant in fastapi flask django; do
  uv --project "$ROOT/$variant" sync --locked --no-dev
done
for variant in spring-boot spring-webflux kotlin-jvm; do
  build_maven "$ROOT/$variant/pom.xml"
done
(
  cd "$ROOT/aspnet-core"
  dotnet publish -c Release -o target/publish
)
