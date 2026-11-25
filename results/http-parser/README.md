# HTTP/1.x parser diagnostics

The reference is commit `35d8e938e2590a7c6221af2e7c7c0a8f432978bb`. The candidate is `a9491738ddb4591f881e00ed8006e651f52b56dd` from [PR #193](https://github.com/christian-draeger/kotlin-native-perf-lab/pull/193). Compiler and binary identities are in [environment.json](native-arm64/environment.json).

## Linux ARM64 release binaries

Ubuntu 24.04.5 on one Apple M3 Max OrbStack VM. The one-worker server had guest CPUs 0 and 1. The load generator used guest CPUs 2 and 3, two threads and 64 HTTP/1.1 connections. Five matched rounds alternated order per endpoint. Every trial had a verified response before 5 seconds of warm-up and 8 seconds of measurement. All 20 release trials completed with zero recorded socket or HTTP errors. This is an exploratory loopback saturation diagnostic.

| Endpoint | Requests/s before → after | p99 µs before → after | CPU seconds / million before → after | Peak RSS KiB before → after |
| --- | ---: | ---: | ---: | ---: |
| /health | 323,833 → 362,801 | 463 → 468 | 3.16 → 2.83 | 18,080 → 16,628 |
| /json | 238,024 → 271,350 | 946 → 664 | 4.28 → 3.76 | 18,196 → 17,840 |

Columns show medians of the individual trials for each build. The [paired ranges](native-arm64/summary.txt) preserve both improvements and regressions across the five pairs. The median paired request rate increased by 14.31% on /health and 19.17% on /json. /health tail latency varied between pairs. The separate medians were nearly equal, and paired changes ranged from −31.33% to +25.58%. The /json p99 and CPU seconds per million requests improved in every pair. The [raw logs and CSV](native-arm64/) retain successful counts, failures, p50/p95/p99, process CPU and memory.

## JVM parser allocation probe

Five 200,000-request batches per build and lookup mode used one existing buffer and the same representative valid head on the same JVM. Accepted requests were retained in a rotating array to prevent the measurement from losing allocation work to escape analysis. The per-thread allocation counter measured 312 → 224 bytes per parsed request without a lookup and 368 → 280 with one lookup. This probe measures parser allocations on macOS and the JVM. [Raw counters, fixture and toolchain](jvm-allocations/).
