# Kotlin Native Performance Lab

A reproducible, incremental performance experiment: **Ktor/CIO on the JVM**, **Ktor/CIO on Kotlin/Native**, **Rust/Axum**, and later a custom Kotlin/Native HTTP framework.

**Phase 0 baseline validation.** See [results/](results/) for the exploratory measurements and profiling evidence.

## Benchmark dashboard

Browse throughput, tail latency, CPU cost and peak RSS by measurement series and endpoint on [GitHub Pages](https://christian-draeger.github.io/kotlin-native-perf-lab/). New committed measurements under `results/<UTC timestamp>/results.csv` appear after the Pages workflow runs on `main`. The dashboard defaults to the newest series. Optional notes and links for a specific series go in `dashboard/run-notes.json`.

For a local preview, build the static files and serve the output directory.

```bash
python3 scripts/build-dashboard.py /tmp/perflab-dashboard
python3 -m http.server 8765 --bind 127.0.0.1 --directory /tmp/perflab-dashboard
```

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
- Rust and Cargo 1.100.0 for the recorded baseline (`rustup toolchain install 1.100.0 --profile minimal`).
- `curl`, `python3`, `wrk`, `git`; Linux `/proc` for automated CPU/RSS measurements.

## Build

```bash
export RUSTUP_TOOLCHAIN=1.100.0
./scripts/build-all.sh
./scripts/smoke-all.sh
```

The build creates the Rust release binary using `Cargo.lock`, the Kotlin/Native release executable, and a JVM classpath distribution. On a cold build Kotlin/Native downloads its compiler and LLVM tools. `smoke-all.sh` exercises all three builds sequentially over HTTP/1.1. The GitHub Actions workflow runs these checks on Ubuntu 24.04 x86_64.

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

The harness starts a fresh process per variant, endpoint, and repetition. It writes warm-up and measured `wrk` logs, environment and binary checksums, per-run CPU and RSS metrics, and a median [min–max] summary to `results/<timestamp>/`. The local load generator competes with the server for CPU. For controlled comparisons, use a separate load-generator machine and the [benchmark protocol](BENCHMARK_PROTOCOL.md).

Install Linux `perf` to capture separate diagnostic CPU profiles on native Linux.

```bash
PERF_SUDO=1 ./scripts/profile.sh results/diagnostic-profiles
```

The command uses JDK Flight Recorder for Ktor/JVM and `perf` for the Native and Rust binaries. `PERF_BIN` can point to the installed `perf` binary when the distribution wrapper cannot find it.

## Phases

- **Phase 0 (this scaffold):** parity contract, Ktor/JVM, Ktor/Native, Axum, reproducible tooling, and exploratory baseline measurements.
- **Phase 1:** establish a controlled baseline on a dedicated native Linux host and identify limiting resources.
- **Phase 2:** build `kotlin-reactor` proof of concept with `epoll`, thread-per-core, native Buffer pool, and basic HTTP/1.1.
- **Phase 3:** typed routing and generated JSON; compare at every change against the three baselines.
- **Phase 4:** selective allocation analysis and compiler plugin *only if profiles justify it*.

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
