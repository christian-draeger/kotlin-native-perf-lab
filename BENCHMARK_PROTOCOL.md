# Benchmark protocol

## Question

How do **Ktor CIO/JVM**, **Ktor CIO/Kotlin Native**, and **Axum/Tokio** compare on equivalent HTTP handlers? Later compare an independent Kotlin Native reactor as a fourth stack.

The expanded exploratory suite also includes Go/net/http, Hono/Node.js, Elysia/Bun, FastAPI/Uvicorn, and Spring Boot MVC/Tomcat. The same HTTP smoke contract applies to every runtime. Worker configuration follows each documented launcher and CPU/RSS are sampled on the server process. Hosted trials use a fixed warm-up. Confirm steady state for managed runtimes on the controlled hosts before interpreting fine differences.

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
7. Randomize/interleave variant order in higher-quality runs to mitigate drift. The local script rotates variant order each repetition and uses a fixed endpoint order.
8. Run at multiple concurrency levels (e.g. 1, 16, 64, 256). Saturation throughput and low-concurrency latency answer different questions.
9. Use at least 5 repetitions; report median, min/max or interval; investigate outliers.
10. Store exact git SHA, CPU, kernel, JDK/Gradle/Kotlin/Ktor and rustc/cargo versions.

## Local harness and limitations

- The load generator and server share one host. Use the local runs to exercise the methodology and identify hypotheses on that host.
- The Lua callback reads p50, p95, and p99 from stock wrk's interval-corrected latency distribution after each run. Report the median and min–max of the run-level p99s. A pooled request-level p99 would require the original latency samples.
- The CPU numerator is the change in cumulative process user and system time from `/proc/<pid>/stat` around the measured load. Its boundary includes starting and draining the load generator, and its accounting excludes the load generator and any CPU activity that Linux charges outside the server process.
- The denominator for a valid run is the wrk completed response count. wrk flags HTTP statuses 400 and above and socket errors. The separate smoke checks require HTTP 200, verify the payloads and headers, and exercise keep-alive. wrk accepts 3xx and does not inspect payloads under load.
- `VmHWM` includes process startup and warm-up. The CSV also records resident memory at the end of each measurement.
- Check the warm-up and measurement logs before interpreting the table. A trial with zero completed responses, socket errors, or wrk HTTP errors is retained and left out of the aggregate. The fixed warm-up provides the same preconditioning workload across variants. Confirm JVM steady state separately.
- Profile separately from throughput collection. Use JFR for JVM execution and allocation samples and Linux perf for Native and Rust on native CPU hardware. Report the endpoint and where samples were collected.

## Record the investigation

Use one issue/PR per optimization hypothesis, include baseline comparison and failures; don't discard slower variants.
