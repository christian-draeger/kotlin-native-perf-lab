# Traffic endurance on Linux

The endurance workflow uses the same HTTP/1.1 application mix as the standalone k6 study, including JSON, a catalog, three upload sizes, the paced download and the simulated dependency wait. Each variant runs a release binary on a fresh GitHub-hosted x86_64 VM. Both jobs assign two logical CPUs to the server and two to k6. ktServe uses one epoll worker and Axum uses two Tokio workers. Runner hardware can differ between jobs, so compare the phases of each server within its own run.

| Phase | Duration | Offered requests/s |
| --- | ---: | ---: |
| Warm-up | 1 min | 800 |
| Steady | 10 min | 800 |
| Burst | 2 min | 1,600 |
| Recovery | 6 min | 800 |
| Burst | 2 min | 3,200 |
| Recovery | 10 min | 800 |

After warm-up the schedule lasts 30 minutes. One k6 execution keeps the same scenarios active through the rate changes. Each operation has 48 preallocated virtual users and a two-second request timeout. The measured phases have one-minute rows with actual starts, validated responses, errors and successful-response p50, p95 and p99. Latency samples belong to the window in which k6 started the request. The paced download can dominate the mix-wide tail.

`scheduled_minus_started` measures the window's offered count minus the starts attributed to that window. A small signed value can include scheduler timing across window boundaries. The raw k6 summary reports the executor's exact whole-run `dropped_iterations`. The archived process samples capture server and generator CPU and RSS about every five seconds. Window CPU is bracketed by the nearest observations and the rows include the actual time between those observations. Peak RSS is the process watermark since startup. GC data is outside this experiment.

Run the **ktServe traffic endurance** workflow with the `endurance` profile for the full schedule. Pull requests to the harness run the same phases at reduced rates and durations. The artifact contains the phase windows, five-second process samples, unmodified k6 summary, preflight and postflight, and host and binary metadata. Both HTTP contract checks cover the same routes and payloads as the application-mix study. [Application mix protocol](../BENCHMARK_PROTOCOL.md#compare-a-larger-json-response)
