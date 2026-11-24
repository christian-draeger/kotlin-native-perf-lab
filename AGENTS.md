# Codex collaboration rules

Mission: make a Kotlin/Native HTTP server competitive with Rust/Axum **without falsifying benchmark conclusions**.

1. Preserve API parity across `competitors/ktor/` and `competitors/rust-axum/`; any new payload or endpoint must exist in all baseline servers.
2. Keep the baseline implementations stable once they are validated; place our new framework in a separate `components/ktserve/` directory. Never silently rewrite the baseline to improve the comparison.
3. Record each benchmark's host CPU, kernel, architecture, toolchain versions, commits, parameters, raw logs, p50/p95/p99, requests/sec, CPU-seconds and peak RSS. Never invent values.
4. Benchmarks must use release binaries, warmed servers, identical connection count, HTTP version, endpoint and response semantics. Keep the load generator off the server machine for a serious comparison.
5. Make one architectural improvement per commit/PR and remeasure all variants, including negative results. Prefer measurements and flamegraphs to speculative tuning.
6. Unit/smoke tests are required for behavior changes. Benchmarks are not correctness tests.
7. Native memory must have documented ownership and lifetimes. No undefined behavior or unbounded leaks as a shortcut to higher requests/sec.
8. Avoid a Kotlin compiler fork until profiling proves it is needed. Start with a user-space reactor, buffer pool and generated routes.
9. Be candid: if CI cannot compile or benchmarks cannot run, report that explicitly and create an issue rather than claiming success.
10. Never benchmark a GitHub Actions shared runner as if it were a controlled, published comparison.
11. Keep application handlers, routing, serialization and HTTP state independent of OS I/O details. Platform backends implement a small transport contract covering operation progress, completion, cancellation and buffer ownership. Validate the same behavior across backends and measure abstraction overhead when changing this boundary.
12. Evaluate Kotlin and maintained kotlinx APIs first when they fit the contract. Keep the Kotlin implementation when it meets the measured performance and resource goals. Measure a focused alternative before replacing it with native interop, and keep OS-specific code behind the adapter.

Immediate assignment: see `CODEX_FIRST_TASK.md`.
