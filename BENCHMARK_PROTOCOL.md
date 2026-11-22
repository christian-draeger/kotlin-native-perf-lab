# Benchmark protocol

## Question

How do **Ktor CIO/JVM**, **Ktor CIO/Kotlin Native**, and **Axum/Tokio** compare on equivalent HTTP handlers? Later compare an independent Kotlin Native reactor as a fourth stack.

## Measurements

- Throughput (HTTP requests/second).
- Latency distribution, in particular p50, p95 and p99, at fixed concurrency.
- Process CPU seconds divided by successfully served requests (CPU-seconds / 1M requests).
- Peak resident memory (RSS / VmHWM), including steady-state observations.
- Error responses, non-2xx responses, timeouts and unexpected payload mismatches.

## Fairness

1. Same physical host; pin cores and frequency/governor where possible; note SMT and NUMA.
2. Prefer a separate load-generator host. Otherwise acknowledge competition for CPU.
3. Same endpoint, JSON schema and payload, same HTTP protocol, keep-alive and connection count.
4. Disable external application logging for all contenders. No debug builds or profilers during the reported runs.
5. Warm up each process before measurement, especially JVM. Re-launch fresh server processes for repetitions when measuring peak RSS.
6. Keep GC defaults for baseline; record JVM GC and Native GC settings. Tuning must be reported as a separate variant.
7. Randomize/interleave variant order in higher-quality runs to mitigate drift. Local quick script uses fixed order for simplicity.
8. Run at multiple concurrency levels (e.g. 1, 16, 64, 256). Saturation throughput and low-concurrency latency answer different questions.
9. Use at least 5 repetitions; report median, min/max or interval; investigate outliers.
10. Store exact git SHA, CPU, kernel, JDK/Gradle/Kotlin/Ktor and rustc/cargo versions.

## Limitations of starter benchmark.sh

- It uses `wrk` and `/proc`, and therefore requires Linux for process CPU/RSS.
- Its load generator runs locally by default. Treat results as exploratory, not publication-grade.
- The p99 from the `wrk` summary describes each run's request-level sample; no pooled cross-run p99 is inferred.
- Process `/proc` CPU time is cumulative user+system and does not attribute load-generator CPU.
- `VmHWM` is high-water RSS over the entire process life, including warmup.
- Failed response bodies should be detected by the independent smoke checks; wrk itself does not validate JSON bodies.

## Record the investigation

Use one issue/PR per optimization hypothesis, include baseline comparison and failures; don't discard slower variants.
