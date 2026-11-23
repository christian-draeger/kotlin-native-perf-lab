# Benchmark protocol

## Question

How do **Ktor CIO/JVM**, **Ktor CIO/Kotlin Native**, **Axum/Tokio**, and **NativeServe** compare on equivalent HTTP handlers?

The expanded exploratory suite also includes Go/net/http, Hono/Node.js, Elysia/Bun, FastAPI/Uvicorn, and Spring Boot MVC/Tomcat. The same HTTP smoke contract applies to every runtime. Worker configuration follows each documented launcher and CPU/RSS are sampled on the server process. Hosted trials use a fixed warm-up. Confirm steady state for managed runtimes on the controlled hosts before interpreting fine differences.

## Measurements

- Throughput (HTTP requests/second).
- Latency distribution, in particular p50, p95 and p99, at fixed concurrency.
- Process CPU seconds divided by successfully served requests (CPU-seconds / 1M requests).
- Peak resident memory (RSS / VmHWM), including steady-state observations.
- Error responses, non-2xx responses, timeouts and unexpected payload mismatches.

## Fairness

1. Same physical host; pin cores and frequency/governor where possible; note SMT and NUMA.
2. Use a separate load-generator host for controlled results. Label same-host runs as exploratory.
3. Same endpoint, JSON schema and payload, same HTTP protocol, keep-alive and connection count.
4. Disable external application logging for all contenders. No debug builds or profilers during the reported runs.
5. Warm up each process before measurement, especially JVM. Re-launch fresh server processes for repetitions when measuring peak RSS.
6. Keep GC defaults for baseline; record JVM GC and Native GC settings. Tuning must be reported as a separate variant.
7. Randomize/interleave variant order in higher-quality runs to mitigate drift. The local script rotates variant order each repetition and uses a fixed endpoint order.
8. Run at multiple concurrency levels (e.g. 1, 16, 64, 256). Saturation throughput and low-concurrency latency answer different questions.
9. Use at least 5 repetitions; report median, min/max or interval; investigate outliers.
10. Store exact git SHA, CPU, kernel, JDK/Gradle/Kotlin/Ktor and rustc/cargo versions.

## Run with a separate load machine

Use two Linux machines on a private network and synchronize their clocks. Install `wrk` on the load machine and configure an OpenSSH host alias with a verified host key and noninteractive authentication. The load host must reach the server's chosen interface and TCP range. Use a distinct physical load machine for controlled results.

Build on the server, check that the commit is clean and keep load and benchmark unrelated processes off the pinned cores. Run the harness on the server and choose its interface explicitly. The example uses 60 consecutive ports starting at 20080. Allow one port per trial. The core suite uses 12 ports per repetition, the expanded suite 27, and the additional competitors alone 15.

```bash
./scripts/build-all.sh
BIND_HOST=192.0.2.10 PORT=20080 LOAD_SSH=perflab-load \
  RUNS=5 WARMUP=10 DURATION=30 THREADS=4 CONNECTIONS=64 ./scripts/benchmark.sh
```

The harness opens an SSH control connection, compares machine IDs, estimates clock offset and stages the same Lua reporting script on the load host. All HTTP traffic travels directly from the load host to the server. Server-side `/proc` samples bracket the measured SSH command, so the CPU window includes SSH command setup and teardown. The measured latency and throughput come from wrk's own elapsed interval. Results include the clock offset bounds, both CPU and affinity records, versions, hashes, trial ports and response error counts. Review host isolation and load-generator headroom before including a run in a controlled comparison.

## Local harness and limitations

- The load generator and server share one host. Use the local runs to exercise the methodology and identify hypotheses on that host.
- The Lua callback reads p50, p95, and p99 from stock wrk's interval-corrected latency distribution after each run. Report the median and min–max of the run-level p99s. A pooled request-level p99 would require the original latency samples.
- The CPU numerator is the change in cumulative process user and system time from `/proc/<pid>/stat` around the measured load. Its boundary includes starting and draining the load generator, and its accounting excludes the load generator and any CPU activity that Linux charges outside the server process.
- The denominator for a valid run is the wrk completed response count. wrk flags HTTP statuses 400 and above and socket errors. The separate smoke checks require HTTP 200, verify the payloads and headers, and exercise keep-alive. wrk accepts 3xx and does not inspect payloads under load.
- `VmHWM` includes process startup and warm-up. The CSV also records resident memory at the end of each measurement.
- Check the warm-up and measurement logs before interpreting the table. A trial with zero completed responses, socket errors, or wrk HTTP errors is retained and left out of the aggregate. The fixed warm-up provides the same preconditioning workload across variants. Confirm steady state with the pilot below before using controlled results.
- Profile separately from throughput collection. Use JFR for JVM execution and allocation samples and Linux perf for Native and Rust on native CPU hardware. Report the endpoint and where samples were collected.

## Pilot warm-up and concurrency

Set `WARMUP_WINDOWS` to at least three to test steady state. Each wrk window uses the planned workload for `WARMUP` seconds. Three consecutive error-free windows must have at most 10% spread from minimum to maximum requests per second. Ktor/JVM and Spring Boot also need an unchanged HotSpot compiled-method count over those windows. The first qualifying window ends the warm-up. The cap is `WARMUP × WARMUP_WINDOWS` seconds of target load. The harness keeps every raw window, compiler snapshot and stability decision. It records measured trials that reach the cap as unstable and excludes them from the summary statistics.

On the target hardware, use the pilot to choose a fixed preconditioning budget that covers every variant at each concurrency. Keep the thresholds and window length fixed during the pilot and report when a hardware or workload combination needs a longer cap. Publish measurements using the chosen fixed warm-up so every variant has the same preconditioning. The hosted CI series use a short fixed warm-up and remain exploratory.

For latency, use one thread and one connection. For throughput, run separate suites at 16, 64 and 256 connections with a fixed thread count that divides each count. Apply the same warm-up rule and repetition count to every variant at each concurrency.

## Record the investigation

Use one issue/PR per optimization hypothesis, include baseline comparison and failures; don't discard slower variants.
