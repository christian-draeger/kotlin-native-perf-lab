# Phase-0 toolchains and environment

## Local measurement environment

| Item | Recorded value |
|---|---|
| Benchmark start | 2026-11-22 22:15:58 UTC |
| Measured source | `bc832d4edb7ff89847b31db5677a4a574fcb2ba7`, clean working tree |
| Physical host | Apple M3 Max arm64, 12 performance and 4 efficiency cores, 64 GiB RAM, macOS 27.2.1 (26B131), Darwin 27.2.0 |
| Linux environment | OrbStack 2.2.5, Ubuntu 24.04.5 LTS (Noble), kernel `7.2.12-orbstack-00394-gbecdd6130068` |
| Container image | Project's existing `knpl-phase0-ubuntu:local`, image ID `sha256:54eaca1f45922362f3ef9b551b7c026137a30a665bc72d2d2d112d5108e17d96` |
| Execution architecture | `linux/amd64` userspace under x86_64 translation on the ARM VM. Both Native and Rust executables are ELF64 x86-64. |
| Guest resources | 16 visible CPUs, CPU set 0–15, no container CPU quota or memory cap, 16,423,848 KiB guest memory |
| Build tools | Gradle 9.7.0 (revision `3defbfc59d757b873d787b2261de5c7f8a00970a`), OpenJDK 21.0.13.1 (Ubuntu `21.0.13.1+2-1-24.04.1-Ubuntu`) |
| Kotlin and Ktor | Kotlin multiplatform and serialization plugins 2.4.21, Kotlin/Native compiler distribution 2.4.21 for Linux x86_64, Ktor CIO 3.6.1 |
| Rust | rustc 1.100.0 (`0ef1bc386059596dada8fb65b50425aab2ea637a`, LLVM 23.1.5), Cargo 1.100.0 (`3acc79c0c`), Axum 0.8.9, Tokio 1.54.1, Serde 1.0.229 |
| Load generator | wrk Debian build `4.1.0-4build2` (epoll), Python 3.12.3 for sampling and aggregation |
| Parameters | HTTP/1.1 keep-alive over loopback port 18080, 2 wrk threads, 16 connections, 2 s wrk timeout, 10 s warm-up, 5 s target measurement, five trials per endpoint and variant |
| CPU placement | Shared VM CPU pool and loopback, with stock runtime thread counts. Affinity, frequency, and background load were uncontrolled. |

The server and load generator shared the physical host and container. The reported CPU time and RSS are Linux process counters from the translated environment. See the [raw environment record](20261122T221558Z/environment.txt) for flags, the JVM classpath, and artifact SHA-256 checksums.

## Build and runtime profile parity

| Variant | Build and runtime |
|---|---|
| Ktor/JVM | Kotlin target JVM 21, Gradle `jvmDist` packages the JAR and runtime classpath. The harness launches HotSpot directly. JVM and GC override variables were unset. HotSpot selected G1, mixed mode, a 262,781,568-byte initial heap, and a 4,204,505,088-byte maximum heap. |
| Ktor/Native | Gradle `linkReleaseExecutableNative` for `linuxX64("native")`, using Kotlin/Native's optimized release configuration. The project sets no GC or allocator overrides. Kotlin 2.4 uses concurrent mark-and-sweep by default. |
| Rust/Axum | Cargo `build --release --locked` with optimization level 3, thin LTO and one code generation unit. No custom compiler flags or allocator in the project. Exact crate versions are in [Cargo.lock](../rust-axum/Cargo.lock). |

The Kotlin/Native build opts into the POSIX foreign API only at the Native entry point and disables Gradle's default hierarchy because the single host target is explicitly named `native`. For the measurement build, Gradle file watching was disabled through `GRADLE_OPTS=-Dorg.gradle.vfs.watch=false` after an event watcher warning in OrbStack. Both Kotlin variants use the same routes, model and JSON serializer configuration.

Each trial started a new process and exercised the selected endpoint for 10 seconds before recording metrics. This gives the JVM time to load classes and compile hot code, with the same preconditioning load for Native and Rust. JVM steady state remains unverified. In the separate JFR diagnostic run, HotSpot was still compiling methods roughly 14.5 seconds into the profile after the same 10-second warm-up. [JFR evidence](profiles.md)

[Gradle 9.7.0](https://docs.gradle.org/9.7.0/release-notes.html), [Ktor Native server](https://ktor.io/docs/server-native.html), [Kotlin 2.4 Native GC defaults](https://kotlinlang.org/docs/whatsnew24.html#default-concurrent-marking-in-garbage-collector), [wrk 4.1.0](https://github.com/wg/wrk/blob/4.1.0/src/wrk.c).
