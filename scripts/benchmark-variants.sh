# Shared server lists for the benchmark and smoke scripts.
core_variants=(ktor-jvm ktor-native rust-axum kotlin-reactor)
competitor_variants=(go-http hono-node elysia-bun fastapi spring-boot)
case "${BENCHMARK_SUITE:-core}" in
  core) variants=("${core_variants[@]}") ;;
  expanded) variants=("${core_variants[@]}" "${competitor_variants[@]}") ;;
  competitors) variants=("${competitor_variants[@]}") ;;
  *) echo 'BENCHMARK_SUITE must be core, expanded or competitors.' >&2; exit 2 ;;
esac
