# Runtime release diagnostics

This diagnostic used a local ARM64 Linux VM on Apple Silicon. It ran the unchanged application-mix capacity metric functions with release binaries and a single default ktServe worker. The server used CPUs 8–9 and the generator used CPUs 10–11. The trial order was counterbalanced. Other tasks used disjoint CPU masks on the same host, so these are exploratory results.

The initial run used a five-second cold window, five-second warm-up, and ten-second measurement window with two observations per cell. The 800 requests/s load qualified. Calibration did not qualify 3,200 requests/s. All raw data, including the unqualified tier, is retained under initial. Environment details, binaries and harness hashes are recorded beside the logs.

## Qualified initial results at 800 requests/s

All six trials passed response targets and recovery with zero failures or dropped arrivals.

| Release code | Successful req/s median | Overall p99 median | CPU seconds per million, both samples | Peak RSS median, KiB |
| --- | ---: | ---: | ---: | ---: |
| Baseline d567e14 | 800.70 | 55.08 ms | 72.437, 57.450 | 37,672 |
| Response deadlines and optional blocking routes ed0b0b5 | 800.65 | 54.22 ms | 63.694, 61.204 | 37,918 |
| Initial REST routing and policy 7d61cf3 | 800.55 | 55.46 ms | 176.118, 57.464 | 37,360 |

The #182 change showed no adverse signal in this limited comparison. One #188 CPU sample was substantially higher and also recorded roughly twice the generator CPU of the neighboring samples. The other sample closely matched baseline CPU. A follow-up with the final REST release is needed to distinguish a regression from run-to-run variation.

## Intermediate REST repeat

Four counterbalanced pairs compared ed0b0b5 with 3c4de96 at 800 requests/s. The load qualified in all eight trials. CPU cost changed by +54.65%, 0.00%, +67.54%, and −32.10% across pairs. The two high REST CPU trials also had higher generator CPU, so the observations did not isolate the cause. These results triggered a dispatch change and are retained under intermediate-rest.

## Optimized routing release

Commit 65fefff keeps ordinary GET and POST outside the optional routing and policy path. GET dispatch only constructs an asynchronous handler closure after matching a suspending or blocking route. The following diagnostics compare that commit with ed0b0b5. The tested binary hashes are recorded beside raw results.

| Workload | Release | Successful req/s median | p50 | p95 | p99 | CPU seconds per million | Peak RSS median, KiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Calibrated mixed 800/s | Deadline baseline | 800.6 | 0.201 ms | 51.959 ms | 55.082 ms | 96.166 | 37,534 |
| Calibrated mixed 800/s | Optimized REST | 800.5 | 0.200 ms | 51.989 ms | 55.120 ms | 90.569 | 37,590 |
| Closed-loop JSON | Deadline baseline | 228,761 | 58 µs | 130.5 µs | 280.5 µs | 4.521 | 19,360 |
| Closed-loop JSON | Optimized REST | 219,871 | 60 µs | 142.5 µs | 382.5 µs | 4.708 | 19,170 |

All eight optimized mixed trials passed response targets, delivery and recovery. The paired CPU cost changes were +5.97%, −32.98%, −1.31%, and +2.56%. The large CPU increases from the intermediate repeat did not recur in these four pairs.

All eight JSON trials passed the workload's HTTP and transport checks. They used two wrk threads, 16 persistently reused connections, four seconds of warm-up, and six measured seconds. The optimized throughput ranged from 187,746 to 252,149 requests/s. Paired changes were −5.94%, −17.91%, +0.22%, and +8.86%. The saturated results remain inconclusive on this local VM. The qualified fixed-load numbers support the observed 800/s configuration. A production capacity or performance-equivalence claim needs independent hosts and a repeatable comparison.

## Frozen production milestone compared with the pre-production snapshot

Both exact release sources were linked in the same workspace with Kotlin/Native 2.4.21. The measured source commits were 35d8e938e2590a7c6221af2e7c7c0a8f432978bb and cdec4ca0269a0769ca682567bb9042ea39feb2fe. The SHA-256 of each executable is in the combined-core input manifest.

The closed-loop /json workload used one default ktServe I/O worker, sixteen persistent connections, two wrk threads, six warm-up seconds and eight measured seconds per trial. The releases alternated launch order for five pairs. The local ARM64 Linux VM ran the origin on CPUs 0–1 and wrk on CPUs 2–3. There were no other agent timed workloads. All ten trials passed response, warm-up, and transport checks.

| Release | Req/s median | p50 | p95 | p99 | CPU seconds per million | Peak RSS median, KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Pre-production baseline | 245,169 | 56 µs | 114 µs | 171 µs | 4.170 | 18,704 |
| Frozen production candidate | 270,205 | 50 µs | 104 µs | 164 µs | 3.747 | 18,876 |

The candidate's median throughput was 10.21% higher. Paired throughput changes were +6.66%, −2.78%, +36.42%, +10.49%, and +7.74%. CPU cost was lower in all five pairs, with a median paired change of −7.53%. The individual p99 values ranged from 158 to 489 µs at baseline and from 148 to 354 µs on the candidate. The median absolute p99 improved, with tail-latency wins in two of the five pairs. Each trial remains in the archive.

This is an exploratory saturated direct HTTP/1.1 result for one workload and architecture. Use independent-host evidence for broadly applicable throughput or tail-latency claims.
