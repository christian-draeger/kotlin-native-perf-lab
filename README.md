# Kotlin Native Performance Lab

A reproducible, incremental performance experiment: **Ktor/CIO on the JVM**, **Ktor/CIO on Kotlin/Native**, **Rust/Axum**, and later a custom Kotlin/Native HTTP framework.

**Status: Phase 0 scaffold. No benchmark results have been measured yet.** This is a starting point, not a performance claim.

## Identical API contract

| Method | Endpoint | Response | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | `ok` (`text/plain`) | Minimal HTTP overhead |
| GET | `/json` | `{"id":42,"name":"benchmark-user","active":true}` | Typed JSON serialization |
| GET | `/users/123` | Same JSON with `id=123` | Routing, parsing, dynamic serialization |

The Ktor servers use the *same* `commonMain` routes and serializers. Both use the **CIO** engine, to isolate JVM-vs-Native effects. The Axum implementation uses the same response model and serde-based JSON.

## Prerequisites

- Linux x86_64 recommended for a reproducible initial baseline; supported Kotlin/Native host targets also cover common macOS architectures.
- JDK 21 and Gradle 9.7 (the optional Gradle wrapper can be generated with `gradle wrapper --gradle-version 9.7.0`).
- Rust stable + Cargo.
- `curl`, `python3`, `wrk`, `git`; Linux `/proc` for automated CPU/RSS measurements.

## Build

```bash
./scripts/build-all.sh
```

This builds release-profile Rust and release Kotlin/Native, plus a JVM classpath distribution. **No benchmarks run during builds.** For a cold build, Kotlin/Native downloads a compiler toolchain.

## Run any server

```bash
PORT=8080 ./scripts/run-server.sh ktor-jvm
PORT=8080 ./scripts/run-server.sh ktor-native
PORT=8080 ./scripts/run-server.sh rust-axum
```

Use another terminal to check the exact API contract:

```bash
./scripts/smoke.sh http://127.0.0.1:8080
```

## Measure (Linux)

```bash
RUNS=5 WARMUP=10 DURATION=30 THREADS=4 CONNECTIONS=64 ./scripts/benchmark.sh
```

Raw `wrk` logs, CSV records and a median summary (`summary.md`) are stored in `results/<timestamp>/`. The harness starts a **fresh server process for each variant, endpoint and repetition**, performs a warm-up and uses `/proc` CPU time and peak RSS. The local load generator competes for CPU; for publishable results, use a separate load-generator machine and adjust the measurement procedure. See [BENCHMARK_PROTOCOL.md](BENCHMARK_PROTOCOL.md).

## Phases

- **Phase 0 (this scaffold):** parity contract, Ktor/JVM, Ktor/Native, Axum, reproducible tooling, and unmeasured baseline.
- **Phase 1:** run and validate baseline on one specified Linux host; save raw data and exact versions, identify limiting resource.
- **Phase 2:** build `kotlin-reactor` proof of concept with `epoll`, thread-per-core, native Buffer pool, and basic HTTP/1.1.
- **Phase 3:** typed routing and generated JSON; compare at every change against the three baselines.
- **Phase 4:** selective allocation analysis and compiler plugin *only if profiles justify it*.

## Publish to GitHub

The GitHub connector in this chat cannot create a repository. This local Git repository is already initialized with a starter commit. To create the **private** repository under your account and push it:

```bash
cd kotlin-native-perf-lab
gh auth login  # skip if already authenticated
gh repo create christian-draeger/kotlin-native-perf-lab --private --source=. --remote=origin --push
```

`gh` is GitHub's official CLI. You can switch visibility later in GitHub's repository settings.

## Codex

`AGENTS.md` defines project rules and a bounded first Codex task. In Codex, connect this GitHub repository and use the task prompt in [CODEX_FIRST_TASK.md](CODEX_FIRST_TASK.md). Do not claim results until executing the workload.

## Methodology and honesty

- The JVM baseline and Kotlin/Native share CIO; Rust uses Axum/Tokio. We are comparing real server stacks, **not** language instruction throughput.
- Rust and Kotlin/Native compile to release binaries; the JVM uses a warmed runtime.
- Content-type, response correctness, HTTP version, keep-alive, concurrency, body bytes and machine setup should be checked before interpreting results.
- Do not treat a single run or a GitHub Actions runner as an authoritative performance benchmark.

## Docs

- [Ktor Native server](https://ktor.io/docs/server-native.html)
- [Ktor server engines](https://ktor.io/docs/server-engines.html)
- [Axum](https://docs.rs/axum/latest/axum/)
