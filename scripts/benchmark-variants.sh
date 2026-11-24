# Shared server lists for the benchmark and smoke scripts.
core_variants=(ktor-jvm ktor-native rust-axum ktserve)
competitor_variants=(go-http hono-node elysia-bun fastapi spring-boot aspnet-core quarkus vertx micronaut http4k javalin spring-webflux flask django)
case "${BENCHMARK_SUITE:-core}" in
  core|application) variants=("${core_variants[@]}") ;;
  workers)
    variants=(ktserve-reference ktserve ktserve-workers-2 "${core_variants[@]:0:3}")
    if [[ "${COMPARE_WORKER_LISTENERS:-false}" == true ]]; then variants+=(ktserve-workers-2-reuseport); fi
    ;;
  io-backends) variants=(ktserve-epoll ktserve-io-uring) ;;
  expanded) variants=("${core_variants[@]}" "${competitor_variants[@]}") ;;
  competitors) variants=("${competitor_variants[@]}") ;;
  *) echo 'BENCHMARK_SUITE must be core, application, expanded, competitors, workers or io-backends.' >&2; exit 2 ;;
esac
