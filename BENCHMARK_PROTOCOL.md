# Benchmark protocol

## Question

How do **Ktor CIO/JVM**, **Ktor CIO/Kotlin Native**, **Axum/Tokio**, and **ktServe** compare on equivalent HTTP handlers?

The expanded exploratory suite also includes Go/net/http, Hono/Node.js, Elysia/Bun, FastAPI/Uvicorn, Flask/Waitress, Django/Uvicorn, Spring Boot MVC/Tomcat, Spring Boot WebFlux/Reactor Netty, ASP.NET Core/Kestrel, Quarkus REST, Vert.x Web, Micronaut, http4k and Javalin. The same HTTP smoke contract applies to every runtime. Worker configuration follows each documented launcher and CPU/RSS are sampled on the server process. Hosted trials use a fixed warm-up. Confirm steady state for managed runtimes on the controlled hosts before interpreting fine differences.

Hosted measurements use a separate VM for each architecture and endpoint. The frameworks for one endpoint run sequentially on that VM, and its results keep their own environment and raw logs. Compare frameworks within that endpoint and architecture. The VM's load generator and server still share resources, and hosted CPU models can vary across endpoints and runs. Run controlled measurements on the dedicated hosts below.

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

Build on the server, check that the commit is clean and keep load and benchmark unrelated processes off the pinned cores. Run the harness on the server and choose its interface explicitly. The example uses 60 consecutive ports starting at 20080. Allow one port per trial. Per repetition, the core suite uses 12 ports, the expanded suite 54, and the additional competitors alone 42. With `BENCHMARK_ENDPOINT` set to one of `/health`, `/json` or `/users/123`, reserve one port per server per repetition.

```bash
./scripts/build-all.sh
BIND_HOST=192.0.2.10 PORT=20080 LOAD_SSH=perflab-load \
  RUNS=5 WARMUP=10 DURATION=30 THREADS=4 CONNECTIONS=64 ./scripts/benchmark.sh
```

The harness opens an SSH control connection, compares machine IDs, estimates clock offset and stages the same Lua reporting script on the load host. All HTTP traffic travels directly from the load host to the server. Server-side `/proc` samples bracket the measured SSH command, so the CPU window includes SSH command setup and teardown. The measured latency and throughput come from wrk's own elapsed interval. Results include the clock offset bounds, both CPU and affinity records, versions, hashes, trial ports and response error counts. Review host isolation and load-generator headroom before including a run in a controlled comparison.

<a id="compare-nativeserve-workers"></a>

## Compare ktServe workers

The `workers` suite interleaves the prior ktServe release, one and two candidate workers, Ktor/JVM, Ktor/Native, and Axum. Provide the executable and source SHA of the prior release. On dedicated hosts, use `LOAD_SSH` as above. For a same-host rehearsal, set disjoint `SERVER_CPUS` and `LOAD_CPUS` using taskset CPU lists. Both CPU sets and the reference binary checksum are recorded.

In GitHub Actions, run the **Benchmark dashboard** workflow with suite `workers` and the run ID of a successful **Ubuntu smoke** workflow on `main` as `worker_reference_run`. The worker suite can run against a PR branch. Each Linux runner uses separate two-CPU affinity sets for server and load. It archives saturation measurements at 64 connections and fixed-rate `/json` results at 20,000 and 40,000 requests/s. These runs use one GitHub-hosted VM per cell and remain exploratory. Worker artifacts are available on the workflow run.

The fixed-rate artifact includes Linux package, core and SMT sibling data for each visible CPU and the actual process masks. When the four x86_64 CPUs can form two sibling pairs, the same runner repeats the fixed-rate trials with the alternate placement. One placement keeps server and load on different cores with two siblings each. The other gives each process one sibling on each core. Both placements keep CPU IDs disjoint.

An additional diagnostic run enables per-worker accept and parsed-request totals during a 3-second, 20,000 requests/s trial. The totals include the initial health request. The latency and throughput trials run with counters disabled.

Enable **compare worker listeners** in the workflow or set `COMPARE_WORKER_LISTENERS=true` locally to add two workers with separate `SO_REUSEPORT` listeners to the same trials. The two listener variants use the same release binary, process CPU budget and connection limits. Compare accepted connections and handled requests separately. A balanced accept count can still lead to uneven load if persistent connections send different request rates. The diagnostic samples are exploratory.

Dashboard benchmarks on `main` reuse the ARM64 release artifact when a successful Ubuntu smoke run produced it for the exact benchmark commit. The build falls back to cross-compilation when the verified artifact is unavailable. The raw environment record includes `native_build_run_id`.

Review the actual delivered rate and failures before comparing p99 at fixed load. Keep the one-worker result alongside the prior release to spot regressions in the default path. Use the two-host setup to decide on a new default.

## Compare framework engines

Run **Actions → ktServe integration benchmarks** on `main` for a comparison within each framework. Select one app, `jvm` for both JVM apps or `all` for all three. Each row uses the same application code, routes and response semantics for all its launch modes. [Workflow](.github/workflows/integration-benchmarks.yml) · [Dashboard engine comparisons](https://christian-draeger.github.io/kotlin-native-perf-lab/#integration-benchmarks)

| Application | Engines |
| --- | --- |
| Ktor on Kotlin/Native | CIO, ktServe |
| Ktor on the JVM | CIO, Netty, ktServe |
| Spring Boot WebFlux on the JVM | Reactor Netty, ktServe |

The workflow defaults to **k6** for mixed traffic at 200 and 800 offered requests per second. Each of the six operations has its own fixed arrival rate and preallocated pool of 16 virtual users. Every iteration sends one HTTP/1.1 request. The same request mix runs in every engine process.

| Share | Operation |
| ---: | --- |
| 40% | `GET /json` serves a small typed object. |
| 25% | `GET /catalog/{id}` alternates IDs and serializes 32 nested records with UTF-8 text. |
| 10% | `POST /upload` reads 1 KiB. |
| 5% | `POST /upload` reads 24 KiB. |
| 10% | `POST /upload` reads 64 KiB. |
| 10% | `GET /download` streams 32 KiB plus three bytes with a 50 ms application delay between chunks. |

The HTTP/1.1 preflight checks payloads and streamed framing. k6 checks HTTP version, status, content type and response payload on each request. Every engine receives 15 seconds of cold-start load at the same rate before the warm-up and measured windows. The CSV and raw summaries retain the cold-start failures and dropped arrivals. A failed response in any phase invalidates the trial. A drop or arrival-count deviation beyond tolerance invalidates the warm-up or measured window. Raw k6 summaries include the successful duration distribution of each operation. Throughput divides successful responses by the scheduled measured window. Use p99 and process cost at the same offered rate to compare engines. Download latency includes the application delay. [k6 fixed arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)

Select `wrk` in the workflow to repeat the historical fixed-concurrency runs for `/json`, the 24 KiB upload and `/download`. The default core and expanded rankings still use wrk. Worker fixed-rate probes use Vegeta and the experimental HTTP/2 comparison uses h2load. Compare only runs with matching workload and load-tool settings.

Select `native-wakeup` to compare timed polling, eventfd and CIO in the same Ktor Native release binary. Choose `upload` or `download` as the experiment endpoint. Each mode uses the same connection count, CPU affinity and `wrk` preconditioning. The raw logs record the wake-up mode, and the HTTP preflight checks both the response bytes and the chunked download framing.

The GitHub-hosted comparison runs each engine in an independent server process and records raw workload logs, the measured revision and host information. The server and load generator share the VM with separate CPU affinity sets. Treat these results as exploratory. Controlled conclusions still require two physical hosts, validated JVM warm-up and at least five valid repetitions per cell.

To check the Ktor JVM fixture locally with JDK 21, build its distribution and run the HTTP smoke checks across its engines.

```sh
fixture=components/ktserve/benchmarks/ktor-jvm
./components/ktserve/gradlew -p "$fixture" installDist
python3 scripts/benchmark-integrations.py --family ktor-jvm \
  --executable "$fixture/build/install/ktserve-benchmark-ktor-jvm/bin/ktserve-benchmark-ktor-jvm" \
  --output "$(mktemp -d)" --smoke-only
```

## Compare a larger JSON response

The `application` suite runs the four core variants on `GET /catalog/123`. Each handler builds and serializes 32 records with nested stock data, a request-derived identifier and UTF-8 text. The smoke check verifies exact bytes on a reused HTTP/1.1 connection for two IDs and verifies HTTP 400 for a malformed ID. The endpoint is registered when an application suite is selected.

The opt-in `application-mix` smoke suite adds streamed request and response bodies and an asynchronous wait to the same four reference applications. Run `BENCHMARK_SUITE=application-mix ./scripts/smoke-all.sh` after building them. `POST /upload` validates each byte and reports the received count, with a 64 KiB limit. `GET /download` sends two chunks separated by a 50 ms delay. `GET /wait` models a 20 ms asynchronous dependency wait. The smoke suite exercises the existing JSON/catalog endpoints on the same connection and keeps the mixed fixture separate from the catalog-only load series.

Use **Actions → Benchmark dashboard → application** for the Linux x86_64 and ARM64 rehearsal. Pages lists the runs under **Application workloads**. Use `BENCHMARK_SUITE=application` with the two-host Linux command for a controlled rerun. This is a single application workload in the [workload ladder](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/114).

## Local harness and limitations

- The load generator and server share one host. Use the local runs to exercise the methodology and identify hypotheses on that host.
- The Lua callback reads p50, p95, and p99 from stock wrk's interval-corrected latency distribution after each run. Report the median and min–max of the run-level p99s. A pooled request-level p99 would require the original latency samples.
- The CPU numerator is the change in cumulative process user and system time from `/proc/<pid>/stat` around the measured load. Its boundary includes starting and draining the load generator, and its accounting excludes the load generator and any CPU activity that Linux charges outside the server process.
- The stock `wrk` harness uses GNU `time` on the load host when available, including over SSH. The raw load log records user and system CPU time, elapsed wall time and peak resident memory. The trial CSV records their CPU time sum as `load_cpu_seconds`, peak RSS as `load_peak_rss_kib` and CPU seconds divided by wall seconds as `load_cpu_equivalents`. A value of `1.0` means `wrk` occupied about one logical CPU on average. Compare this diagnostic with the load affinity set and delivered traffic. An average can hide a saturated thread, scheduling delays or contention elsewhere on the host.
- The denominator for a valid run is the wrk completed response count. wrk flags HTTP statuses 400 and above and socket errors. The separate smoke checks require HTTP 200, verify the payloads and headers, and exercise keep-alive. wrk accepts 3xx and does not inspect payloads under load.
- `VmHWM` includes process startup and warm-up. The CSV also records resident memory at the end of each measurement.
- Check the warm-up and measurement logs before interpreting the table. A trial with zero completed responses, socket errors, or wrk HTTP errors is retained and left out of the aggregate. The fixed warm-up provides the same preconditioning workload across variants. Confirm steady state with the pilot below before using controlled results.
- Profile separately from throughput collection. Use JFR for JVM execution and allocation samples and Linux perf for Native and Rust on native CPU hardware. Report the endpoint and where samples were collected.

## Pilot warm-up and concurrency

Set `WARMUP_WINDOWS` to at least three to test steady state. Each wrk window uses the planned workload for `WARMUP` seconds. Three consecutive error-free windows must have at most 10% spread from minimum to maximum requests per second. For the JVM servers, the increase in HotSpot compilation time must also be at most 1% of JVM elapsed time in each of those windows. The first qualifying window ends the warm-up. The cap is `WARMUP × WARMUP_WINDOWS` seconds of target load. The harness keeps every raw window, compiler snapshot and stability decision. It records measured trials that reach the cap as unstable and excludes them from the summary statistics. [HotSpot compiler counters](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jstat.html)

Start calibration with `WARMUP=10 WARMUP_WINDOWS=6`. In the exploratory SSH pilot, five three-second windows were too short for Ktor/JVM and one ktServe trial at 16 connections. A longer Ktor/JVM `/users/123` pilot recorded gains of 1.06 and 0.23 seconds of compilation time in early ten-second windows, followed by 0.07, 0.07 and 0.04 seconds while throughput spread was 3.27%. Compilation tasks continued during that low-activity period. The 60-second cap and compiler-time threshold are starting values for the dedicated-host pilot.

On the target hardware, use the pilot to choose a fixed preconditioning budget that covers every variant at each concurrency. Keep the thresholds and window length fixed during the pilot and report when a hardware or workload combination needs a longer cap. Publish measurements using the chosen fixed warm-up so every variant has the same preconditioning. The hosted CI series use a short fixed warm-up and remain exploratory.

For latency, use one thread and one connection. For throughput, run separate suites at 16, 64 and 256 connections with a fixed thread count that divides each count. Apply the same warm-up rule and repetition count to every variant at each concurrency.

## Record the investigation

Use one issue/PR per optimization hypothesis, include baseline comparison and failures; don't discard slower variants.
