# Outbound HTTP/1.1 connection reuse

[Issue #179](https://github.com/christian-draeger/ktserve/issues/179) · [Benchmark change](https://github.com/christian-draeger/ktserve/pull/210) · [Matched HTTP run](https://github.com/christian-draeger/ktserve/actions/runs/45727255618) · [Symbolized profile run](https://github.com/christian-draeger/ktserve/actions/runs/45730245085)

The end-to-end run used a GitHub-hosted x86_64 Linux VM with an AMD EPYC 7763 and separate logical CPU masks for the two-CPU frontend, the one-CPU k6 driver and the one-CPU Python origin. It measured three repetitions at 200 and 800 offered requests/s with the same HTTP/1.1 contract, 15 seconds of preconditioning, 10 seconds of warm-up and 8 seconds of measured traffic. All 30 trials were valid. The driver observed zero response mismatches and zero dropped iterations during scored windows. The origin observed all 6,030 expected cancellations, with zero replies to cancelled deadline requests. The scenario includes expected HTTP 502 for the deliberately dropped upstream connection and HTTP 504 for deadline expiration.

| Frontend and outbound client | CPU s per million at 200/s | CPU s per million at 800/s | Fast p99 at 800/s (ms) | Peak RSS at 800/s (MiB) | Median requests on reused connections at 800/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| ktServe, Native CIO fresh | 1290.524 | 1319.488 | 12.141 | 217.09 | 0 / 6404 |
| ktServe, Kotlin socket fresh | 841.646 | 833.854 | 9.993 | 65.40 | 0 / 6404 |
| ktServe, Kotlin socket pooled | 405.237 | 387.258 | 9.578 | 50.69 | 5762 / 6404 |
| Axum, Hyper fresh | 149.626 | 146.783 | 8.368 | 9.45 | 0 / 6404 |
| Axum, Hyper pooled | 93.516 | 84.322 | 8.437 | 9.66 | 5763 / 6404 |

All values are trial medians. The paired socket comparison at 800/s measured 53.6% lower frontend CPU from connection reuse alone. Replacing the fresh CIO client with the fresh Kotlin socket client measured 36.8% lower frontend CPU. The socket and Hyper pooled cases both recovered after deadline and disconnect cases. The full CSV includes per-outcome p50, p95 and p99, origin CPU and RSS, successful delivery and cancellation. At 200/s the Kotlin socket disconnect p99 rose from 2.683 to 3.645 ms with reuse. Axum Hyper with reuse still used substantially less CPU than the Kotlin socket fixture in this scenario.

## Separate client-only measurements

The first run executed three uninstrumented batches of 1,200 sequential calls per configuration without a frontend listener, targeting the same 5 ms origin. The process-level CPU measurement includes startup and shutdown. Median CPU s/million were 1158.333 for fresh CIO, 725 for fresh Kotlin sockets, 266.667 for pooled Kotlin sockets, 200 for fresh Hyper and 116.667 for pooled Hyper. Every pooled batch opened one socket and reused it for 1,199 requests.

A separate `strace` pass for 400 calls counted 400 `connect` calls in the fresh Kotlin socket case and one in the pooled case. The pooled Kotlin socket case reduced combined read/write calls from 4,808 to 2,420 and wait/wake calls from 29,792 to 11,191. The syscall counts are diagnostic. Virtual-memory syscalls describe memory mapping activity and do not count Kotlin object allocations. The first run recorded successful elevated CPU samples, while symbolization failed an ownership check. `profile-rerun/` contains the client-only repeat with the corrected report step.

Keep this narrow Kotlin socket parser in the benchmark. The paired run justifies reuse as a performance direction and the remaining gap warrants testing a general-purpose maintained HTTP client before exposing a production API. The historical CIO and Hyper fresh comparison has its own launch mode. These co-located measurements support exploratory attribution on the tested fixture.

The profile-only repeat found 666.667, 375 and 133.333 CPU seconds per million for Native CIO fresh, Kotlin socket fresh and Kotlin socket pooled. Fresh and pooled Hyper measured 108.333 and 58.333 respectively. Absolute costs differed between the two VMs and both orderings agreed. The five `perf` reports retained 273, 168 and 57 samples for the Kotlin cases and 42 and 19 for Hyper. They show Native allocator, coroutine dispatcher and Ktor selector frames. Some kernel frames were unsymbolized and small sample counts limit allocation and scheduling attribution. Raw perf recordings are attached to the workflow runs.
