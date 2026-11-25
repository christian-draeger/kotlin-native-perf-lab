# Linux epoll listener recovery

[Issue #183](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/183) · [PR #192](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/192) · [Linux release smoke](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45565742109)

The local candidate smoke starts its own loopback release process and temporarily narrows its descriptor budget. Admission pauses for new sockets, established clients continue to get valid responses, queued admission resumes after the descriptor budget is restored, and shutdown succeeds while admission is paused. Both the single-worker and two-worker cases recorded zero process CPU ticks over roughly 0.60 seconds of pressure at the test's sampling resolution. The earlier 1,024-connection shared admission bound is exercised separately in the same hosted smoke workflow.

## Paired default-path diagnostic

The baseline uses source commit `35d8e938e2590a7c6221af2e7c7c0a8f432978bb`. The candidate uses `aaf0da265238a28000ebdd52fc092b0eabaf7a23`. Both Linux ARM64 executables were linked locally in release mode using Gradle 9.7.0, Kotlin/Native 2.4.21, and the same Java runtime. Each binary supplies one level-triggered epoll worker with the same listener and response settings. The server is pinned to logical CPUs 0–1, and k6 to CPUs 2–3 in the same network-isolated OrbStack Linux VM. Each pair rotates baseline and candidate order. The recorded generator version is k6 2.4.0.

The existing capacity harness calibrates with the same payload and latency fixtures, preconditions and warms each run for 5 seconds each, then measures 15 seconds at the offered rate with the same per-operation HTTP/1.1 keep-alive workload and 96 virtual users per scenario. The artifacts preserve counts, timings, process CPU and RSS, per-operation latency, scenario budgets and raw load logs. An immutable copy of the runtime identifiers, harness parameters, and SHA-256 digests is included in each result folder.

The result scope is two fixed load levels on a shared local VM. Treat the ranges as exploratory evidence about this change. The follow-up repeats 3,200 requests/s after the other local paired diagnostics finish.

## Initial round

Values below are medians across three eligible pairs per load level.

| Offered req/s | Successful req/s, before → after | Overall p99 in ms | Server CPU-s per million responses | Peak RSS in MiB | Trials meeting every operation target |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 800 | 800.400 → 800.467 | 55.590 → 55.306 | 129.935 → 94.112 | 36.582 → 36.973 | 3/3 → 3/3 |
| 3,200 | 3,200.400 → 3,200.333 | 54.961 → 54.974 | 80.407 → 78.538 | 44.891 → 46.000 | 3/3 → 2/3 |

All 12 runs had zero failed responses and zero dropped starts. The third candidate trial at 3,200 had a 64 KiB upload p99 of 21.123 ms, crossing the 20 ms operation target. The aggregate median hides this tail excursion. The initial raw measurements and summaries retain it.

## Quiet repeat of the initial implementation

The follow-up used the original candidate and baseline at 3,200 requests/s. Each passed the three operation targets sets, with zero failed responses or dropped starts. The 64 KiB upload p99 ranged from 5.120 to 7.957 ms in the candidate trials. Overall p99 was 54.101 → 54.204 ms, server CPU-s per million responses was 52.486 → 56.663, and peak RSS was 42.617 → 47.465 MiB. Candidate CPU cost was 4.0–9.0% higher in the three pairs.

## Optimized listener poll path

Commit `d8d5a5fab9193971a85bd7758a707c1426377d86` returns from listener handling as soon as registration is unchanged, before reading the backoff deadline or computing a retry timeout. The retry path uses the same delay and listener ownership semantics. The final Linux ARM64 release passed the same one- and two-worker pressure smoke. [Final hosted smoke](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45567979251)

| Offered req/s | Successful req/s, before → after | Overall p99 in ms | Server CPU-s per million responses | Peak RSS in MiB | Trials meeting every operation target |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 800 | 800.400 → 800.400 | 55.172 → 55.261 | 132.434 → 147.426 | 37.449 → 37.410 | 3/3 → 3/3 |
| 3,200 | 3,200.600 → 3,200.333 | 55.240 → 54.355 | 81.440 → 59.794 | 45.125 → 46.035 | 3/3 → 3/3 |

Each level calibrated successfully. The final 12 trials had zero failed responses and zero dropped starts. Paired CPU cost at 800/s varied from −17.7% to +13.2%. The two builds have overlapping CPU observations in this diagnostic at 800/s. At 3,200/s, paired CPU cost ranged from −26.6% to −1.8%, and the original repeat's consistent extra cost was not reproduced with the optimized code. Relative performance and capacity conclusions for production still require independent hosts.
