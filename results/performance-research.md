# ktServe performance research

Research baseline `43870c3673a6b1603bb0b407c727e1cea0b92528`, 24 November 2026. Goal is comparable application performance against Axum on documented CPU and memory budgets. The current CI results use shared Linux VMs. The controlled decision remains tracked in [#16](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/16) and [#21](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/21).

## Current evidence

The expanded CI run measured 18 stacks, three endpoints and both architectures. All 324 trials passed the harness checks. For each endpoint, the frameworks took turns on the same VM with 16 HTTP/1.1 connections, two wrk threads, ten seconds of warm-up and five measured seconds. The table compares the one-worker ktServe and Axum release medians within each VM. CPU means server process CPU seconds per million responses. p99 is the median of three trial-level p99s at each server's achieved throughput. [Workflow](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45345839468) · [Raw results](https://github.com/christian-draeger/kotlin-native-perf-lab/tree/0f627bea0062838f7e5d3dc6cfd656b082560add/results)

| Linux | Route | ktServe / Axum req/s | ktServe share | p99 ms, ktServe / Axum | CPU s/M, ktServe / Axum | Peak RSS MiB, ktServe / Axum |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| x86_64 | `/health` | 168,895 / 207,703 | 81% | 0.186 / 0.628 | 6.07 / 6.57 | 16.90 / 4.23 |
| x86_64 | `/json` | 144,734 / 203,020 | 71% | 0.226 / 0.595 | 7.10 / 6.83 | 16.93 / 4.23 |
| x86_64 | `/users/123` | 131,609 / 197,311 | 67% | 0.246 / 0.568 | 7.88 / 7.18 | 16.81 / 4.16 |
| ARM64 | `/health` | 110,181 / 133,293 | 83% | 0.303 / 1.147 | 9.20 / 11.93 | 15.95 / 3.31 |
| ARM64 | `/json` | 90,774 / 130,438 | 70% | 0.351 / 1.034 | 11.19 / 11.06 | 15.95 / 3.33 |
| ARM64 | `/users/123` | 87,849 / 134,013 | 66% | 0.359 / 0.941 | 11.63 / 11.82 | 15.83 / 3.33 |

ktServe was near Axum in process CPU cost for the JSON routes. Peak RSS was about four to five times higher even though absolute use stayed below 17 MiB in these six ktServe cells. Throughput was below the proposed 90% target in each cell. The p99 observations describe different achieved loads. Compare them again at common offered rates before attaching a latency claim. The process CPU counter leaves kernel work charged to other threads or processes outside its scope.

Micronaut at the same `/json` x86_64 cell reached 223,065 req/s at 10.65 CPU s/M and 1.566 ms p99. Its ARM64 result was 91,522 req/s at 26.89 CPU s/M and 1.406 ms p99. That x86_64 process consumed roughly 2.38 vCPU equivalents during measurement, compared with 1.03 for ktServe. These values are inferred from delivered rate and process CPU consumption. Thread count, VM behavior, warm-up and runtime costs are separate variables. All stacks retain their existing launch configuration in the expanded series. [Micronaut fixture](../competitors/kotlin-jvm/micronaut/src/main/kotlin/codes/draeger/Application.kt) · [Micronaut configuration reference](https://docs.micronaut.io/4.10.x/configuration-reference/)

### Evidence worth keeping

- Two epoll workers increased exploratory 64-connection `/json` saturation throughput 56% on x86_64 and 97% on ARM64. Saturation p99 rose on both. At 40k offered requests/s, p99 ranges overlapped and server CPU per request rose moderately. Four workers lost throughput in three of four ARM64 pilot cells and cost more CPU in all four. An instrumented follow-up observed one worker handle 92.7% of requests in one ARM64 sample. SMT placement changed absolute CPU cost and p99 on x86_64 while the higher fixed-load two-worker CPU cost persisted within both tested placements. [Worker trials](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/17) · [Topology and distribution evidence](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/87)
- The parser's range scanning reduced temporary strings. The follow-up five ARM64 pairs on the same Apple Silicon Linux host moved `/users/123` from 194,829 to 210,193 req/s and 5.02 to 4.93 CPU s/M. A separate GC-epoch diagnostic estimated 3,146 to 2,228 accounted heap bytes per response. The diagnostic is sensitive to allocator reporting granularity. [Parser evidence and negative outliers](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/69)
- The Kotlin serialization sink experiment kept wire parity on 768 DTOs. With the current contiguous response representation, the existing String-to-UTF-8 path beat both fresh and reused sinks across all tested payloads. The result includes the copy at the sink boundary. A segment-aware transport or streamed output would change that boundary and needs a fresh hypothesis. [Serializer decision](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/84)
- The same-binary io_uring and epoll pilot found lower p99 for io_uring in all eight exploratory cells and higher peak RSS. Throughput rose in three x86_64 cells and fell in all four ARM64 cells. For example x86_64 `/json` at 16 connections rose from 84,105 to 88,995 req/s while ARM64 fell from 93,201 to 83,493. Keep epoll as the standard Linux transport. [Backend results](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/54#issuecomment-7120457077)
- The public JVM and macOS transports, Ktor engines, WebFlux adapter, HTTP/2 and Windows prototypes establish correctness and integration paths. Each needs evidence at its own protocol, application, runtime and platform. [Integration and protocol roadmap](../ROADMAP.md)

### Fresh CPU profile

A separate release profile of `5be7ccd` used x86_64 Intel Xeon Platinum 8573C, Ubuntu 24.04.5 and Linux 7.0.0-1017-azure. It sampled `/users/123` after ten seconds of warm-up at 16 connections with the client on the same VM. The ktServe stack export has 1,519 CPU-clock records and 629 with no emitted stack frames. The flat profile identifies UTF-8 conversion (4.08% for Kotlin string-to-native conversion and 2.04% for creating a Kotlin string from UTF-8), allocation (2.63% in `CustomAllocator::Allocate`), GC sweep (2.57% in the leading sweep symbol) and request parsing (1.38% in `RequestBuffer.parse`). These are separate leaf symbols from one sample. Inlining and absent frames limit attribution to application operations.

The leading kernel leaf `_raw_spin_unlock_irqrestore` was 12.44%. All 189 emitted ktServe stacks at that leaf included TCP, IP, socket-buffer or network-interface frames. The same symbol had 174 networking and 28 futex stacks among 202 Axum records at that leaf. In Ktor/Native, 537 of 578 records at that leaf had futex frames. Generic kernel lock symbols therefore give different traces per runtime. The ktServe result motivates reduction of allocation and encoding work and a separate socket-path profile, with no evidence here that replacing Kotlin coroutine startup would improve the inline endpoint. [Profile stacks, checksums and environment](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45354419219)

## Design review

### Connection ownership and CPU budgets

ktServe keeps HTTP lifecycle state local to an I/O worker. Short handlers run inline. One shared listener and one epoll instance per worker let the kernel send exclusive wake-ups for pending accepts. Exclusive wake-up semantics limit redundant wake-ups and allow one or more interested epoll instances to receive an event. That mechanism leaves load balance to the interaction between readiness, accept draining, and the workload. One process-wide cap is partitioned across the workers. A hot keep-alive socket can keep a worker busy even if listener accept counts are balanced. [ktServe transport](https://github.com/christian-draeger/kotlin-native-perf-lab/blob/df77b3336404a1eb84c1126346aecbd57c43b016/components/nativeserve/TRANSPORT.md) · [Linux epoll_ctl](https://man7.org/linux/man-pages/man2/epoll_ctl.2.html)

Per-worker `SO_REUSEPORT` listeners give Linux separate accept queues and connection selection. This may improve average distribution, while a connection assigned to a busy worker can still wait behind its queue or meet that worker's local connection limit. A separate acceptor with bounded round-robin handoff is another testable option. It permits explicit capacity decisions and introduces queue operations, notifications and transfer of descriptor ownership. Netty's acceptor and child event-loop groups illustrate the latter family of designs. Measure both even and skewed connection activity before choosing either. [Linux socket options](https://man7.org/linux/man-pages/man7/socket.7.html) · [NGINX accept mutex](https://nginx.org/en/docs/ngx_core_module.html#accept_mutex) · [Netty ServerBootstrap](https://github.com/netty/netty/blob/4.2/transport/src/main/java/io/netty/bootstrap/ServerBootstrap.java)

Single-worker replicas are a separate scale-out comparison. They give each process its own heap and failure boundary. Aggregate the CPU and memory of all replicas and include the load-balancing layer in the measured deployment path. Use identical CPU budgets and frontend configuration when comparing process replicas with a multi-thread process.

Axum's listener accepts connections and spawns a Tokio task for each connection. Its async handlers run on a multi-thread runtime with task scheduling across worker threads. This differs from a fixed owner per ktServe socket. Compare application stacks with a shared CPU mask, then repeat with their documented deployment defaults. Record JVM thread groups, Tokio worker count, ktServe workers, physical-core versus SMT siblings and client mask. A separate one-versus-two configuration within Micronaut isolates the effect of event-loop count better than a cross-framework comparison. [Axum serve source](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/src/serve/mod.rs) · [Tokio runtime](https://docs.rs/tokio/1.54.1/tokio/attr.main.html#multi-threaded) · [Budget experiment #116](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/116)

### Coroutine dispatch and response completion

An immediate route stays on its I/O owner. The suspending path schedules a task on `Dispatchers.Default`, transfers a response through a one-slot channel and lets the event loop observe completion. At the research baseline, asynchronous body and producer progress also relied on short polls. Timing the parsed-request to dispatch, handler slot occupancy, handler-complete to I/O resume and final response separately would distinguish scheduling, app wait and readiness delays. Instrument these timestamps in diagnostic runs so the measured fast path avoids per-request probes. [HTTP loop](../components/ktserve/src/commonMain/kotlin/codes/draeger/ktserve/HttpServer.kt) · [Kotlin dispatchers](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/)

A Linux eventfd notification can wake epoll when a coroutine publishes a response. That saves an idle poll delay and introduces a syscall and synchronization per notification. High concurrent load may already keep the loop active. A bounded ready-ID queue with a coalesced empty-to-nonempty wake-up is a follow-up if profiles identify scanning pending handlers or repeated notifications. Test concurrent completion, EOF, cancellation, body backpressure and shutdown before extending it to macOS, JVM or other adapters. A real upstream workload should be evaluated separately from an immediately returning suspending block. [Linux eventfd](https://man7.org/linux/man-pages/man2/eventfd.2.html) · [Handoff experiment #112](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/112)

Starting a coroutine on the I/O owner until its first suspension is a different experiment. It can skip scheduling for cooperative work that completes immediately and can also delay every socket on that owner if the handler runs CPU-heavy or blocking code before yielding. The public off-loop handler contract provides the baseline. Only explicitly cooperative workloads are candidates for the alternative. [Kotlin coroutine start modes](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-start/)

### Allocation, framing and kernel paths

ktServe reserves a receive array bounded by the request-head limit per connection. The plain response path allocates encoded headers and concatenates the body, and typed JSON runs through the current String-to-UTF-8 encoder. Kotlin/Native uses a shared heap, tracing GC and thread-local allocation pages. Per-request allocation and retention under many open connections can therefore have different costs. Profile release allocations and GC independently and compare RSS after idle, warm, and sustained-load phases. Use that evidence for a capped worker-owned receive pool or incremental allocation. Preserve ownership across close, partial writes and all backends. [Request buffer](../components/ktserve/src/commonMain/kotlin/codes/draeger/ktserve/RequestBuffer.kt) · [Wire encoding](../components/ktserve/src/commonMain/kotlin/codes/draeger/ktserve/Http.kt) · [Kotlin/Native memory manager](https://kotlinlang.org/docs/native-memory-manager.html) · [Buffer pool #18](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/18)

Five fresh processes of the ARM64 release fixture held up to 512 additional idle HTTP/1.1 connections after one successful request on each. The local diagnostic ran on Ubuntu 24.04 in OrbStack on an Apple M3 Max with the standard epoll transport and Kotlin 2.4.21. Each sample forced a collection and recorded the allocator's reported heap and process RSS. One control connection stayed open throughout.

| Additional idle connections | Median heap after GC, MiB | Median RSS, KiB |
| --- | --- | --- |
| 0 | 2.625 | 5,336 |
| 64 | 4.875 | 7,640 |
| 128 | 5.000 | 7,768 |
| 256 | 7.125 | 9,944 |
| 512 | 11.125 | 14,048 |
| 0, one second after close | 3.125 | 5,848 |
| 0, five seconds after close | 3.125 | 5,848 |

The 512-connection change was 8.5 MiB, or 17 KiB per connection by this aggregate estimate. The current receive array alone has 16 KiB capacity. All added descriptors closed. The first post-close sample still reported 11.125 MiB and the next two reported 3.125 MiB. Allocation cost per request and long-run memory bounds require separate measurements. A smaller initial buffer with growth up to the existing header limit would address the idle footprint. Pooling the full-size arrays could address churn if allocation attribution justifies keeping spare arrays. [Raw samples and binary identity](connection-retention/arm64-orbstack.json) · [Diagnostic script](../scripts/diagnose-connection-retention.py)

Profile response copies for dynamic or larger bodies before adding scatter/gather. A two-segment header/body response can reduce copying with `writev` but needs correct partial progress across segments and portable adapter behavior. More syscalls can offset savings on tiny responses. Parameterized route generation is most plausible for many routes, according to the shape of the current lookup. Require a route-cardinality profile before optimizing the sparse benchmark. [Linux writev](https://man7.org/linux/man-pages/man2/writev.2.html) · [Compiled routes #19](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/19)

The level-triggered backend currently accepts a socket and calls `fcntl` to enable nonblocking and close-on-exec flags. `accept4` can set both at acceptance. It is a focused candidate for high connection churn. Batched delivery and io_uring submit/completion behavior are separate questions. Keep the app and kernel version fixed, verify actually supported kernel operations, and include scenarios where network I/O dominates. CPU and RX-queue affinity on real NICs are later tests because loopback lacks that path. [Linux accept4](https://man7.org/linux/man-pages/man2/accept.2.html) · [Linux networking scaling](https://www.kernel.org/doc/html/latest/networking/scaling.html)

## Measurement and decisions

| Tier | What the results can support | Requisite evidence |
| --- | --- | --- |
| Release correctness | Equivalent behavior, safety under concurrency and portable API semantics | Status, MIME and bytes, keep-alive, cancellation, errors, bounded queues, partial progress and shutdown. |
| Paired hosted exploration | Select or discard a candidate on the observed runners | Use one same-binary toggle where possible. Interleave variants, match offered load and record achieved rate, failures, response latency, process CPU, peak RSS, masks and min/max. Run diagnostic counters and profiling separately. |
| Controlled application comparison | Set a production default or claim Axum parity for that workload | Physical server and independent load host, validated headroom and steady state, repeated matched CPU budgets, architecture and protocol, and a predeclared p99 and success objective. |

The workload ladder should preserve the original short plain text, typed JSON and path parameter fixtures. Its next tiers are connection churn, realistic JSON with Unicode and nested values, validated POST, slow and disconnecting clients, a reproducible upstream wait, and streams with bounded producer/consumer speed. Compare higher concurrency, sustained resource use and a load step through overload. Test TLS ingress and HTTP/2 as their own series. Comparable routes should emit dynamic data through their real handlers. [Workload ladder #114](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/114) · [TechEmpower test definitions](https://github.com/TechEmpower/FrameworkBenchmarks/wiki/Project-Information-Framework-Tests-Overview)

Use fixed concurrency for saturation throughput and a fixed-offer sweep for latency. Report successful and failed responses, generator scheduling lag and CPU use. A generator that has reached its own limit can understate overload and should be flagged with the raw trial. Keep trial-level p50/p95/p99, actual duration, CPU and RSS. Report paired ratios and dispersion and keep all invalid trials with their reason. Use the existing 90% throughput and 110% p99/CPU proposal as a starting hypothesis for the targets, set absolute service levels per workload before measuring, and record memory explicitly. [Generator validation #113](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/113) · [Constant-throughput latency measurement](https://github.com/giltene/wrk2) · [The Tail at Scale](https://research.google/pubs/the-tail-at-scale/)

## Experiments in this investigation

| Hypothesis | Measurement |
| --- | --- |
| Separate listeners improve two-worker distribution and service efficiency | [Implementation](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/117) · [Hosted run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45353312293) |
| An event-driven handoff reduces latency for a suspending handler | [Implementation](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/118) · [Hosted run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45354961631) |
| Current release profiles expose the next measurable CPU bottlenecks | [Separate diagnostic run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45354419219) |

### Shared versus per-worker listeners

The same two-worker release binary ran both listener configurations on each runner. All 126 saturation trials were valid across the three routes and both architectures, including reference, one worker, Axum and Ktor. All 90 fixed-rate trials delivered within 2% of the offered rate with HTTP 200 and no measured failures. The separate distribution diagnostics handled about 60,000 requests per mode and CPU placement. The table reports the larger worker's request share, including startup, and the three-trial medians at 40,000 requests/s. [Raw CSV, Vegeta reports and worker counters](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45353312293)

| Placement, server / load masks | Larger worker share, shared → reuse-port | p99 at 40k, ms | CPU at 40k, s/M |
| --- | ---: | ---: | ---: |
| ARM64 `0,1 / 2,3` | 83.1% → 51.3% | 2.550 → 2.628 | 15.25 → 16.25 |
| x86_64 `0,1 / 2,3` | 68.9% → 53.5% | 0.993 → 1.029 | 12.65 → 12.85 |
| x86_64 `0,2 / 1,3` | 60.9% → 54.8% | 0.808 → 0.830 | 13.85 → 14.90 |

The distribution moved toward equal request counts in each diagnostic sample. Saturation throughput changes across the six cells were between −0.85% and +1.77%. Saturation `/json` p99 dropped from 2.153 to 1.667 ms on x86_64 and 1.532 to 1.220 ms on ARM64. At matched 40k, p99 and CPU medians increased in all three placements, with overlapping p99 ranges. At 20k on ARM64, p99 fell from 0.821 to 0.768 ms. Better balance can change saturation behavior while providing a different trade-off below saturation.

In this matrix, two workers with a shared listener reached 240,031 req/s against Axum's 261,825 on x86_64 `/json` (92%) and 187,701 against 200,464 on ARM64 (94%). In the default x86_64 placement at 40k, the shared-listener p99 was 0.993 ms against Axum's 1.196, while CPU was 12.65 against 10.20 s/M. On ARM64 those pairings were 2.550 vs 2.591 ms and 15.25 vs 13.90 s/M. These cells have different relative bottlenecks and do not establish the production gate. Retain the reuse-port configuration as opt-in for controlled repeats and workloads with connection churn. Keep the one-worker application default. [Decision #115](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/115)

### Coroutine handoff and eventfd completion

Each `/json` response was serialized from the same Kotlin value in one release binary with one ktServe I/O worker. The modes were inline, dispatcher with polling, and dispatcher with eventfd completion. The same job assigned disjoint server and client masks for both saturation and fixed-load trials. The matched-load artifacts record masks and CPU topology. HTTP smoke covered parity, concurrent pipelines, disconnects and shutdown on both Linux architectures. The first x86_64 build exposed an eventfd argument binding difference and the corrected executable was measured on both architectures. A Windows IOCP idle-stream stress failure passed on the same commit's retry and in a separate main-branch check. [Linux smoke](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45354897293) · [Windows retry](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45354897044) · [Windows main](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45355766065)

All 36 saturation and 48 fixed-rate trials were valid. Each mode has three repeats per load and architecture. Fixed-rate p99 and CPU medians below are ordered inline, polling, eventfd. The individual counter snapshots, p50/p95/p99, errors, warm-up and raw request distributions are in the artifacts. [Raw handler measurements](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45354961631)

| Linux | Offered req/s | p99 ms, inline / polling / eventfd | CPU s/M, inline / polling / eventfd |
| --- | ---: | ---: | ---: |
| x86_64 | 20,000 | 1.062 / 1.459 / 0.927 | 11.40 / 23.30 / 34.10 |
| x86_64 | 40,000 | 0.949 / 2.031 / 1.278 | 11.60 / 23.60 / 26.10 |
| ARM64 | 20,000 | 0.987 / 1.484 / 1.014 | 18.40 / 31.30 / 36.70 |
| ARM64 | 40,000 | 2.798 / 2.872 / 2.623 | 14.25 / 26.65 / 28.50 |

The eventfd variant reduced fixed-load p99 relative to polling in all four cells and consumed more CPU per request in all four. At saturation, the inline, polling and eventfd medians were 142,798, 91,953 and 106,857 req/s on x86_64 and 95,978, 68,128 and 71,934 on ARM64. Both dispatcher paths retained about 24–26 MiB peak RSS, compared with 17–18 MiB inline. The experiment captures scheduler and completion costs for a small handler that finishes immediately. Upstream waiting and mixed streaming traffic remain separate tests.

The original handler-only eventfd implementation stayed on the experiment branch. The next hypothesis is a bounded completion queue with fewer notifications, followed by an identical application workload with real suspensions. Validate cancellation and wake-up races before considering the change for the default transport. [Prototype #118](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/118) · [Follow-up #112](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/112)

### Separate HTTP response header and body buffers

The `aeaebcc` release executable compared the current contiguous response with two buffers sent by one Linux `sendmsg` call. Both modes used one epoll worker, the same handler, and the same `kotlinx.serialization` path. Each of the six hosted Linux cells ran five interleaved trials per variant, with 16 HTTP/1.1 connections, disjoint server/load CPU masks in the same VM, ten seconds of warm-up and five measured seconds. All 150 trials met the harness criteria, including all 60 ktServe trials. Core Ktor and Axum fixtures ran unchanged alongside both modes. [Raw data, runner metadata and binary checksums](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45362385849)

| Linux | Route | Req/s, contiguous → gathered | Paired req/s change, median [min–max] | p99 ms | CPU s/M | Peak RSS MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| x86_64 | `/health` | 164,474 → 162,464 | −1.23% [−1.26%, −0.60%] | 0.187 → 0.188 | 6.24 → 6.29 | 16.95 → 16.95 |
| x86_64 | `/json` | 141,952 → 141,074 | −1.00% [−1.35%, +1.03%] | 0.223 → 0.220 | 7.24 → 7.28 | 16.95 → 16.95 |
| x86_64 | `/users/123` | 130,811 → 128,968 | −1.15% [−1.60%, −0.87%] | 0.238 → 0.239 | 7.91 → 8.00 | 16.73 → 16.71 |
| ARM64 | `/health` | 108,300 → 109,880 | +0.09% [−1.59%, +2.23%] | 0.298 → 0.295 | 9.34 → 9.23 | 15.95 → 15.96 |
| ARM64 | `/json` | 90,657 → 92,958 | −0.23% [−2.61%, +6.17%] | 0.368 → 0.350 | 11.22 → 10.91 | 15.95 → 15.95 |
| ARM64 | `/users/123` | 85,256 → 83,956 | +0.43% [−4.22%, +1.67%] | 0.369 → 0.374 | 11.98 → 12.17 | 15.83 → 15.83 |

The paired column takes the median of individual ratios within repetition, so it can differ from the ratio of the two medians. For example ARM64 `/json` had a higher overall gathered median and a slightly negative paired median. The x86_64 `/health` and `/users/123` measurements favored contiguous writes in all five repeats. The ARM64 paired ranges included both improvement and regression in every cell. p99 describes saturation at each variant's achieved throughput.

Both Linux architectures passed parity, pipelining, shutdown and rebinding. A local ARM64 Linux test also ran the existing stress fixture through both modes, including its 8 MiB body case, slow readers and cancellation. That check exercised behavior and kept performance conclusions scoped to the three small-response CI fixtures. The two expanded x86_64 Competitor smoke attempts failed on Maven Central rate limiting and have separate tracking. [Linux correctness](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45362389075) · [Maven follow-up #122](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/122)

Keep the contiguous writer and preserve the gathered implementation as a benchmark prototype. The measured changes are small and provide no consistent advantage for these endpoints. A matched larger-payload comparison may change the trade-off between copying and the second buffer lease. That belongs to [#114](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/114). [Prototype #121](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/121) · [Decision #120](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/120)

### Cost of typed JSON on Kotlin/Native

The isolated release probe measured the same constructors and `kotlinx.serialization` settings as the HTTP endpoints. On both hosted architectures, it verified the exact 47-byte `/json` body and 4,445-byte `/catalog/123` body, including Unicode, against independent fixtures. Construction starts from the route ID, String serialization starts from a constructed DTO, and UTF-8 conversion starts from a serialized String. The complete operation performs all three steps. Each cell runs five warmed CPU trials of 50,000 iterations using the standard runtime and five heap diagnostics of 1,024 iterations with manual GC and unpaged allocation. [Raw CPU, wall time, GC, RSS, fixture hashes and host metadata](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45467927333)

| Payload | Phase | x86_64 CPU µs/op | ARM64 CPU µs/op | Diagnostic heap growth B/op, both |
| --- | --- | ---: | ---: | ---: |
| `/json` | Construct DTO | 0.014 | 0.022 | 43 |
| `/json` | Serialize to String | 0.364 | 0.795 | 731 |
| `/json` | Convert to UTF-8 | 0.091 | 0.134 | 83 |
| `/json` | Complete | 0.487 | 0.956 | 851 |
| `/catalog/123` | Construct DTO | 5.701 | 11.772 | 11,819 |
| `/catalog/123` | Serialize to String | 21.442 | 44.156 | 39,875 |
| `/catalog/123` | Convert to UTF-8 | 6.167 | 8.477 | 4,483 |
| `/catalog/123` | Complete | 32.912 | 65.167 | 56,171 |

CPU and heap columns report medians from separate runs. The complete catalog trial had a median of 486 and 490 GC cycles on x86_64 and ARM64, respectively. Timings include any GC activity inside the sampled block. The separate heap estimate comes from occupied managed heap growth within an uninterrupted diagnostic epoch. Each phase has its own setup and GC behavior, so use the measured complete operation for total cost.

String serialization dominated isolated CPU for both payloads. The previous Kotlin sink paid for a copy into the contiguous HTTP response, and gathered writes showed no consistent gain for the small fixtures. A byte-oriented candidate merits HTTP measurement if it removes an intermediate allocation or copy through response completion. The remaining comparison needs matching small and catalog requests at common offered rates, plus cancellation and slow-client resource bounds. [Sink experiment #84](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/84) · [Gathered writes #120](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/120) · [JSON work #133](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/133)

### Level- and edge-triggered epoll

The Linux release comparison used the same binary for both peer modes, 64 HTTP/1.1 connections, and three repeats for `/json` and `/catalog/{id}` on each runner. Both modes passed the extended partial-I/O, keep-alive and shutdown stress. Across one worker, two with a shared listener, and two with `SO_REUSEPORT`, edge-triggered throughput changed between −1.8% and +4.6%, with p99 moving in both directions. Keep level triggering as default. `KTSERVE_EPOLL_TRIGGER=edge` enables the peer-only experiment. The event loop retains readiness when backpressure or bounded work pauses a socket before `EAGAIN`, and listeners stay level-triggered. [Experiment and correctness](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/134) · [Hosted trial data](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45391515745)

### Application wake-ups in the integration adapters

The earlier coroutine trial measured an immediately returning handler. The Ktor Native 24 KiB upload repeatedly hands body progress across a coroutine boundary. The separate, opt-in `KTSERVE_EPOLL_APPLICATION_WAKEUP=eventfd` signals the Linux socket loop when that work can proceed. All 18 main-branch upload trials passed, with 16 concurrent HTTP/1.1 connections and three repeats per engine and architecture. The results are medians from the shared runner, with disjoint CPU masks for server and load. [Native upload data](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45395548753)

| Linux | req/s, polling / eventfd / CIO | p99 ms, polling / eventfd / CIO |
| --- | ---: | ---: |
| x86_64 | 3,315 / 9,192 / 6,293 | 8.776 / 6.362 / 6.012 |
| ARM64 | 3,374 / 10,806 / 6,843 | 7.912 / 5.263 / 6.454 |

The corresponding `/download` pilot streamed 32 KiB plus three bytes with a 50 ms delay between chunks. All 18 HTTP/1.1 trials passed. Each architecture used one release binary, 16 persistent connections, ten seconds of warm-up, five measured seconds, and disjoint server and load CPU masks in the same hosted VM. The table shows three-trial medians. [Raw trials and host metadata](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45457170690) · [Engine dashboard](https://christian-draeger.github.io/kotlin-native-perf-lab/#integration-benchmarks)

| Linux | Wake-up mode | Successful req/s | p99 ms | CPU s / million | Peak RSS MiB |
| --- | --- | ---: | ---: | ---: | ---: |
| x86_64 | ktServe polling | 294.1 | 91.371 | 299.52 | 38.19 |
| x86_64 | ktServe eventfd | 311.1 | 90.538 | 295.06 | 38.72 |
| x86_64 | CIO | 313.3 | 53.571 | 535.71 | 150.38 |
| ARM64 | ktServe polling | 285.3 | 94.773 | 420.46 | 35.82 |
| ARM64 | ktServe eventfd | 305.5 | 91.264 | 484.29 | 36.32 |
| ARM64 | CIO | 313.5 | 53.192 | 746.17 | 123.39 |

Eventfd brought download throughput close to CIO and left a median p99 gap of about 37–38 ms. The x86_64 eventfd trials ranged from 54.440 to 90.947 ms p99. On ARM64, eventfd used about 15% more CPU per response than polling. Keep the current default pending independently driven measurements. [Ktor Native streaming investigation #130](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/130)

#### TCP_NODELAY on Ktor Native downloads

The follow-up held the ktServe wake-up mode at eventfd and changed only the accepted-socket TCP setting. CIO ran from the same Ktor Native release binary as a reference. All 18 hosted trials were valid at 16 persistent connections, with a ten-second warm-up and five measured seconds. Each host used separate CPU masks for the engine and load generator. These are medians of three trials per cell. [Raw trials, host metadata and traces](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45466086351)

| Linux | Engine and TCP setting | Successful req/s | p99 ms | CPU s / million | Peak RSS MiB |
| --- | --- | ---: | ---: | ---: | ---: |
| x86_64 | ktServe eventfd, default TCP | 310.341 | 90.650 | 307.890 | 38.69 |
| x86_64 | ktServe eventfd, TCP_NODELAY | 313.363 | 53.104 | 286.990 | 38.36 |
| x86_64 | CIO | 313.372 | 53.770 | 522.959 | 140.50 |
| ARM64 | ktServe eventfd, default TCP | 304.287 | 91.341 | 500.989 | 36.69 |
| ARM64 | ktServe eventfd, TCP_NODELAY | 313.423 | 53.786 | 497.449 | 36.32 |
| ARM64 | CIO | 313.206 | 53.208 | 733.418 | 114.82 |

In a separate ARM64 trace on a reused connection, strace recorded two successful 8 KiB `sendto` calls at 1.045 and 1.128 ms. The payload packet went out at 41.262 ms, after the client acknowledged the earlier header at 41.238 ms. With `TCP_NODELAY`, the client read those first 16 KiB at 1.258 ms in the corresponding trace. The single-connection diagnostic overlaps the first-chunk wait with the intentional application delay. The table uses the untraced load trials for end-to-end p99. `KTSERVE_EPOLL_TCP_NODELAY=on` is available for opt-in tests. The broader streaming investigation and independently driven comparison remain tracked in [#130](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/130).

JVM ktServe now uses `Selector.wakeup()` as handler output and body buffers become ready. In the exploratory WebFlux `/json` comparison, throughput moved from 9,284 to 58,965 req/s on x86_64 and from 20,581 to 22,410 on ARM64. The Reactor Netty medians in the new run were 85,948 and 12,383 req/s respectively. Reactor Netty retained its upload throughput lead on both architectures. The existing WebFlux adapter copies outbound Spring buffers before handing them to the bounded coroutine queue. A narrower fixed-length publisher path is a separate hypothesis. It must preserve cancellation, demand, buffer release and flush behavior. [JVM implementation](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/137) · [Before](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45384009996) · [After](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45391876507) · [WebFlux comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/45)

### Mixed application traffic with k6

The Ktor Native, Ktor JVM and WebFlux integration suites use k6 at 200 and 800 offered req/s. They check body, content type, status and protocol across small JSON, a nested catalog, 1, 24 and 64 KiB uploads, and a streamed download with a deliberate 50 ms delay. Look at per-operation p99 to separate the upload and API response times from that deliberate delay. The first published series kept six invalid WebFlux warm-up trials with dropped arrivals. A uniform 15-second preconditioning phase now records cold-start drops separately and keeps the zero-drop, zero-error gate for the qualifying warm-up and measurement. [First series](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45392314276) · [Preconditioning and validation](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/140) · [Integration dashboard](https://christian-draeger.github.io/kotlin-native-perf-lab/#integration-benchmarks)
