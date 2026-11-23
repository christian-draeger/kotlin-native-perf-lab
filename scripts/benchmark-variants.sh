# Shared server lists for the benchmark and smoke scripts.
core_variants=(ktor-jvm ktor-native rust-axum kotlin-reactor)
competitor_variants=(go-http hono-node elysia-bun fastapi spring-boot aspnet-core quarkus vertx micronaut http4k javalin spring-webflux flask django)
case "${BENCHMARK_SUITE:-core}" in
  core) variants=("${core_variants[@]}") ;;
  workers) variants=(nativeserve-reference kotlin-reactor nativeserve-workers-2 "${core_variants[@]:0:3}") ;;
  expanded) variants=("${core_variants[@]}" "${competitor_variants[@]}") ;;
  competitors) variants=("${competitor_variants[@]}") ;;
  *) echo 'BENCHMARK_SUITE must be core, expanded, competitors or workers.' >&2; exit 2 ;;
esac
