# Kotlin Native Performance Lab

A reproducible performance lab comparing established HTTP stacks with [NativeServe](kotlin-reactor/README.md), an HTTP framework for Kotlin/Native.

**Phase 0 baselines and the NativeServe reference server.** See [results/](results/) for the exploratory measurements and profiling evidence.

## Benchmark dashboard

Browse throughput, latency, CPU cost and peak RSS by measurement series and endpoint on [GitHub Pages](https://christian-draeger.github.io/kotlin-native-perf-lab/). The NativeServe history charts medians and trial ranges over time at the same recorded architecture, CPU model and load settings. Each point links back to the measured commit and raw logs. New committed measurements under `results/<UTC timestamp>/results.csv` appear after the Pages workflow runs on `main`. Optional notes and links for a series go in `dashboard/run-notes.json`.

To collect hosted Linux series, open **Actions → Benchmark dashboard → Run workflow** on `main` with **Run the Ubuntu x86_64 and ARM64 benchmarks before publishing** enabled. Choose three or five trials per server and endpoint. The optional feature label appears in NativeServe history. Each series has its own raw logs on `benchmark-data` and its own architecture label on Pages. Within each runner, wrk and the servers share one VM. CPU models and host conditions differ between runners.

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
| Spring Boot on Java | A Spring MVC API with embedded Tomcat and Jackson, running in the same JDK family as Ktor/JVM. |

On Linux with the [CI toolchain versions](.github/actions/setup-competitors/action.yml), Maven and JDK 21:

```bash
bash scripts/build-competitors.sh
BENCHMARK_SUITE=expanded ./scripts/smoke-all.sh
BENCHMARK_SUITE=expanded RUNS=5 WARMUP=10 DURATION=5 THREADS=2 CONNECTIONS=16 ./scripts/benchmark.sh
```

Build the original four first with `./scripts/build-all.sh` for the expanded suite. For an independent smoke test of the five additions, use `BENCHMARK_SUITE=competitors`. The default local suite remains the original four.

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

The build creates the Rust release binary using `Cargo.lock`, both Kotlin/Native release executables, and a Ktor JVM classpath distribution. On a cold build Kotlin/Native downloads its compiler and LLVM tools. `smoke-all.sh` exercises the three baselines and NativeServe sequentially over HTTP/1.1. GitHub Actions runs the checks on Ubuntu 24.04 x86_64 and ARM64. CI cross-compiles the Kotlin/Native ARM64 executables on x86_64 and runs them on the ARM64 runner. [Kotlin/Native host support](https://kotlinlang.org/docs/native-target-support.html#hosts)

## Run any server

```bash
PORT=8080 ./scripts/run-server.sh ktor-jvm
PORT=8080 ./scripts/run-server.sh ktor-native
PORT=8080 ./scripts/run-server.sh rust-axum
PORT=8080 ./scripts/run-server.sh nativeserve
```

Use another terminal to check the exact API contract:

```bash
./scripts/smoke.sh http://127.0.0.1:8080
```

## Measure (Linux)

```bash
RUNS=5 WARMUP=10 DURATION=30 THREADS=4 CONNECTIONS=64 ./scripts/benchmark.sh
```

The harness starts a fresh process on a distinct TCP port per trial. It writes warm-up and measured `wrk` logs, the trial ports, environment and binary checksums, per-run CPU and RSS metrics, and a median [min–max] summary to `results/<timestamp>/`. The local load generator competes with the server for CPU. For controlled comparisons, use a separate load-generator machine and the [benchmark protocol](BENCHMARK_PROTOCOL.md).

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
