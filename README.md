# ktServe

ktServe is an HTTP server for Kotlin/Native and the JVM. Use its Kotlin routing API in a standalone service, select it as a Ktor engine, or enable it in a Spring Boot WebFlux application.

The Kotlin Native Performance Lab is the project behind ktServe. Its [GitHub Pages site](https://christian-draeger.github.io/kotlin-native-perf-lab/) introduces the server, explains the architecture, and publishes benchmark results with their measured revisions and conditions.

ktServe is licensed under the [Apache License 2.0](LICENSE). Third-party assets retain their accompanying licenses.

## Try ktServe

| Start with | Example |
| --- | --- |
| A standalone Kotlin service on Linux, Apple Silicon, or the JVM | [Standalone quickstart](components/ktserve/README.md) |
| A Ktor application with the ktServe engine | [Ktor JVM example](components/ktserve/examples/ktor-jvm) · [Engine guide for JVM and Kotlin/Native](components/ktserve/integrations/ktor/README.md) |
| A Spring Boot WebFlux application on ktServe | [Spring Boot example](components/ktserve/examples/spring-webflux) · [Starter guide](components/ktserve/integrations/spring-boot-starter/README.md) |

The library uses the Maven coordinate `io.github.christian-draeger:ktserve` and the Kotlin package `io.github.christian_draeger.ktserve`. [Maven release guide](components/ktserve/RELEASING.md)

ktServe replaces the NativeServe preview identifiers in packages, build coordinates and configuration. Historical benchmark results remain available. [Upgrade from the preview](components/ktserve/README.md#upgrade-from-the-preview)

## Repository layout

| Directory | Contents |
| --- | --- |
| `components/ktserve/` | ktServe and its public Kotlin API, platform implementation and component tests. |
| `components/` | Project-owned components and their modules or standalone builds. |
| `competitors/` | Reference implementations for Rust/Axum, the Kotlin server ecosystem, ASP.NET Core, Go, Hono, Elysia and FastAPI. |
| `scripts/` | Shared build, validation, measurement and profiling commands. |
| `dashboard/` | The benchmark dashboard published to GitHub Pages. |

## Benchmark dashboard

Browse throughput, latency, CPU cost and peak RSS by measurement series and endpoint on [GitHub Pages](https://christian-draeger.github.io/kotlin-native-perf-lab/). The overview opens the latest complete expanded comparison, with Core runs available in the same picker. Framework history charts medians at the same recorded architecture, CPU model and load settings. Use the legend to show individual stacks and the run values to inspect exact numbers and raw logs. The separate ktServe chart shows trial ranges and links each run to its measured commit. New committed measurements under `results/<UTC timestamp>/results.csv` appear after the Pages workflow runs on `main`. Optional notes and links for a series go in `dashboard/run-notes.json`.

To collect hosted Linux series, open **Actions → Benchmark dashboard → Run workflow** on `main` with **Run the Ubuntu x86_64 and ARM64 benchmarks before publishing** enabled. Choose the 18-stack `expanded` suite or the quicker four-stack `core` suite, and three or five trials per server and endpoint. The optional feature label appears in ktServe history. One VM runs each architecture and endpoint pair. Every server in that endpoint comparison runs sequentially on the same VM, with a fresh server process per trial. The dashboard groups the endpoints by workflow and architecture while showing the selected endpoint's actual CPU model and raw logs. The load generator shares that VM with each server. CPU models and host conditions can differ between endpoints and workflow runs.

The engine comparisons use one application per framework and change its HTTP engine. They cover Ktor Native on CIO and ktServe, Ktor JVM on CIO, Netty and ktServe, and Spring Boot WebFlux on Reactor Netty and ktServe. Run **Actions → ktServe integration benchmarks** on `main` to collect Linux x86_64 and ARM64 measurements. GitHub Actions publishes the series to the engine comparison view. [Engine comparisons](https://christian-draeger.github.io/kotlin-native-perf-lab/#integration-benchmarks) · [Integration benchmark protocol](BENCHMARK_PROTOCOL.md#compare-framework-engines)

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

## Additional Linux comparisons

| Stack | What it represents |
| --- | --- |
| Go with `net/http` | A lightweight compiled HTTP service using only the standard library. |
| Hono on Node.js | A small web-standard API on Node's HTTP server through the official adapter. |
| Elysia on Bun | A typed API on Bun's integrated HTTP server. Runtime and framework both differ from Hono on Node. |
| FastAPI on CPython | A typed Python API with Pydantic and one Uvicorn worker using uvloop and httptools. |
| Flask on CPython | A Flask WSGI API in one Waitress process with four application threads. |
| Django on CPython | Async Django views in one Uvicorn ASGI worker using uvloop and httptools, with endpoints that perform no database I/O. |
| Spring Boot MVC | A Java API on embedded Tomcat with Jackson. |
| Spring Boot WebFlux | A Java API using functional WebFlux routes, Reactor Netty and Jackson. The archive includes the native Linux epoll transports for x86_64 and ARM64. |
| ASP.NET Core | A .NET 10 Minimal API on Kestrel with System.Text.Json. |
| Quarkus | Kotlin on HotSpot using Quarkus REST and Jackson, packaged as a fast JAR. |
| Vert.x | A Kotlin Vert.x Web router on Netty with Jackson. |
| Micronaut | Kotlin controllers on Netty using compile-time annotation processing and Jackson. |
| http4k | Kotlin HTTP handlers on Jetty with http4k's Jackson integration. |
| Javalin | Kotlin routes on Jetty with Jackson. |

The dashboard uses purple for the Kotlin server ecosystem, including both Java Spring Boot references, and orange for ktServe. The ranking follows the selected metric.

On Linux with the [CI toolchain versions](.github/actions/setup-competitors/action.yml), Maven, JDK 21 and the [pinned .NET SDK](competitors/aspnet-core/global.json):

```bash
bash scripts/build-competitors.sh
BENCHMARK_SUITE=expanded ./scripts/smoke-all.sh
BENCHMARK_SUITE=expanded RUNS=5 WARMUP=10 DURATION=5 THREADS=2 CONNECTIONS=16 ./scripts/benchmark.sh
```

Build the original four first with `./scripts/build-all.sh` for the expanded suite. For an independent smoke test of the additional stacks, use `BENCHMARK_SUITE=competitors`. The default local suite remains the original four. Set `BENCHMARK_ENDPOINT=/json` to measure one endpoint while applying the same HTTP smoke contract.

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

The build creates the Rust release binary using `Cargo.lock`, both Kotlin/Native release executables, and a Ktor JVM classpath distribution. On a cold build Kotlin/Native downloads its compiler and LLVM tools. `smoke-all.sh` exercises the three baselines and ktServe sequentially over HTTP/1.1. GitHub Actions runs the checks on Ubuntu 24.04 x86_64 and ARM64. CI cross-compiles the Kotlin/Native ARM64 executables on x86_64 and runs them on the ARM64 runner. [Kotlin/Native host support](https://kotlinlang.org/docs/native-target-support.html#hosts)

## Run any server

```bash
PORT=8080 ./scripts/run-server.sh ktor-jvm
PORT=8080 ./scripts/run-server.sh ktor-native
PORT=8080 ./scripts/run-server.sh rust-axum
PORT=8080 ./scripts/run-server.sh ktserve
```

Use another terminal to check the exact API contract:

```bash
./scripts/smoke.sh http://127.0.0.1:8080
```

## Measure (Linux)

```bash
RUNS=5 WARMUP=10 DURATION=30 THREADS=4 CONNECTIONS=64 ./scripts/benchmark.sh
```

The harness starts a fresh process on a distinct TCP port per trial. It writes warm-up and measured `wrk` logs, the trial ports, environment and binary checksums, per-run CPU and RSS metrics, and a median [min–max] summary to `results/<timestamp>/`. The default local load generator competes with the server for CPU. Set `LOAD_SSH` and `BIND_HOST` to run the load on a separate Linux machine. [Two-host benchmark protocol](BENCHMARK_PROTOCOL.md#run-with-a-separate-load-machine)

Install Linux `perf` to capture separate diagnostic CPU profiles on native Linux.

```bash
PERF_SUDO=1 ./scripts/profile.sh results/diagnostic-profiles
```

The command uses JDK Flight Recorder for Ktor/JVM and `perf` for the Native and Rust binaries. `PERF_BIN` can point to the installed `perf` binary when the distribution wrapper cannot find it.

## Project roadmap

The [roadmap](ROADMAP.md) describes the proposed Kotlin/Native framework, how to validate it, and the delivery milestones. Each implementation issue has prerequisites and acceptance criteria in [GitHub Issues](https://github.com/christian-draeger/kotlin-native-perf-lab/issues). [Phase-0 assignment](CODEX_FIRST_TASK.md)

Run the fast local checks with JDK 21, Python 3 and Node.js. Add `--static` to run the shell and dashboard checks alone.

```bash
./scripts/check-fast.sh
```

The GitHub smoke workflow runs the fast checks and x86_64 release validation independently of the ARM64 cross-build. Performance measurements run separately.

## Methodology and honesty

- The JVM baseline and Kotlin/Native share CIO; Rust uses Axum/Tokio. We are comparing real server stacks, **not** language instruction throughput.
- Rust and Kotlin/Native compile to release binaries; the JVM uses a warmed runtime.
- Content-type, response correctness, HTTP version, keep-alive, concurrency, body bytes and machine setup should be checked before interpreting results.
- Do not treat a single run or a GitHub Actions runner as an authoritative performance benchmark.

## Docs

- [Ktor Native server](https://ktor.io/docs/server-native.html)
- [Ktor server engines](https://ktor.io/docs/server-engines.html)
- [Axum](https://docs.rs/axum/latest/axum/)
