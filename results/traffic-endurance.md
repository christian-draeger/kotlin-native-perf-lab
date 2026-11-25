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

## Hosted x86_64 pilot

The first full run measured release commit `ea12f5ae0c21b83de2d6f74c5f2c1447caa3b1b1` on Ubuntu 24.04. All started requests passed HTTP and payload validation during the 30 measured minutes. Each server completed the postflight and recovered to the offered 800 requests/s. The VMs used different CPU models. [CI run and artifacts](https://github.com/christian-draeger/ktserve/actions/runs/45505063246)

| Server | Host CPU | Successful / offered after warm-up | k6 drops including warm-up | One-minute p99 range after warm-up | Process peak RSS |
| --- | --- | ---: | ---: | ---: | ---: |
| ktServe, one worker | Intel Xeon 6975P-C | 1,824,000 / 1,824,000 | 0 | 51.636–52.420 ms | 28.38 MiB |
| Axum, two workers | AMD EPYC 7763 | 1,821,771 / 1,824,000 | 2,229 | 51.624–81.342 ms | 18.26 MiB |

The raw k6 summaries include 47,993 additional validated warm-up responses per server. Their full totals are 1,871,993 for ktServe and 1,869,764 for Axum. The postflight uses a separate HTTP check after k6 exits.

During ktServe's 3,200/s burst, p99 was 51.636–51.640 ms across its two one-minute windows. End RSS moved from 25.07 MiB at the end of the initial steady phase to 26.60 MiB at the end of the final recovery. During Axum's 3,200/s burst, throughput fell to about 3,181 validated responses/s and p99 rose to 78.545–81.342 ms. Its final recovery had a p99 range of 51.695–51.741 ms. The drop and latency observations describe the shared runner and its load-generator budget. They leave dedicated-host capacity unresolved.

[Exact ktServe artifact files](traffic-endurance-data/45505063246/traffic-endurance-ktserve-1/) · [Exact Axum artifact files](traffic-endurance-data/45505063246/traffic-endurance-rust-axum-1/)
