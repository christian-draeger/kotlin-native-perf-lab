# Codex first task — Validate phase-0 baseline

Open this repository in Codex and implement/verify the following as a **single PR**:

1. Build the Ktor/JVM, Ktor/Native and Rust/Axum samples on Ubuntu x86_64. Fix compatibility issues without changing the shared API contract.
2. Run smoke checks for `/health`, `/json` and `/users/123` on all three binaries. Validate the JSON values, HTTP status and headers.
3. Review build profile parity (release optimizations, Native GC config, JVM warm-up). Produce an exact `toolchains.md` in `results/`.
4. Run a preliminary benchmark with at least 5 repetitions per endpoint/variant, recording raw wrk logs and metrics. State clearly if the load generator is on the same host.
5. Summarize median and dispersion, 99th percentile latency, CPU-seconds per 1M requests and peak RSS. Do not average p99 values without explaining limitations.
6. Provide profiles showing whether networking, routing, serialization, allocation or scheduling dominate. Recommend **one** next optimization.
7. Do not start a custom framework yet; that begins after baseline acceptance.

Definition of done: CI smoke green, reproducible instructions, machine and version metadata, raw data committed (no secrets), and a result table with limitations.
