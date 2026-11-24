# HTTP upstream measurements

The four release frontends received persistent HTTP/1.1 requests and used one fresh outbound HTTP connection per request. The same loopback origin replied after 5 ms for 70% of requests and after 40 ms for 20%. It closed the socket without a response for 5%, and held the remaining 5% for 450 ms so the frontend's 150 ms deadline expired. Matching HTTP 502 and 504 responses count as successful handling of those fault scenarios. The origin independently counted socket closures before the delayed reply. [Harness and commands](https://github.com/christian-draeger/kotlin-native-perf-lab/tree/f8cd0c69ae4ef840093171af1729f2f67cb72301/components/ktserve/benchmarks/upstream)

Each hosted run used one shared GitHub Actions VM with disjoint CPU affinity masks of two logical CPUs for the frontend, one for the origin and one for k6. The pilots ran on different hosts and used different preconditioning. Treat each as exploratory same-run evidence. The resource figures cover inbound and outbound work together, including Ktor CIO in ktServe and Ktor, and Hyper in Axum. [Outbound client profiling follow-up](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/179)

## First hosted pilot at 200 requests/s

All 12 trials at 200/s passed on AMD EPYC 9V45 x86_64. The initial 800/s arm passed seven of 12 trials. All five invalid trials failed the strict warm-up gate. Four retained complete measurement windows, while one also skipped six measured starts. The archive retains all 24 trials. The measured commit was `ddb49754777cdd8925cf519ff3e197c149cf81fd`. [Hosted run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45505034997) · [Raw files](45505034997-x86_64)

| Frontend | Fast p99 (ms) | Slow p99 (ms) | Deadline p99 (ms) | Disconnect p99 (ms) | CPU seconds per million matching replies | Peak RSS (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Ktor/JVM | 10.78 | 47.24 | 154.34 | 5.70 | 4,108.48 | 225.46 |
| Ktor/Native | 10.06 | 45.58 | 153.20 | 6.44 | 1,889.03 | 270.37 |
| ktServe | 9.04 | 44.37 | 152.89 | 4.33 | 1,109.73 | 81.03 |
| Axum | 6.79 | 41.89 | 152.15 | 1.00 | 105.99 | 5.41 |

## Repeated hosted pilot at 800 requests/s

The repeat on Intel Xeon 6975P-C x86_64 added 15 seconds of preconditioning and sized the k6 virtual user allocation for each scenario's rate with a 200 ms in-flight budget. The scored gates were unchanged. All 12 trials passed, with 76,848 matching measured replies, zero missed starts, zero mismatches, and all 3,852 measured deadline sockets closed before the origin attempted a late reply. The measured commit was `01fb5ca1b37f915ab3ace231dd918aeacf2c8c86`. [Hosted run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45507381623) · [Raw files](45507381623-x86_64)

| Frontend | Fast p99 (ms) | Slow p99 (ms) | Deadline p99 (ms) | Disconnect p99 (ms) | CPU seconds per million matching replies | Peak RSS (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Ktor/JVM | 9.42 | 44.24 | 152.37 | 3.68 | 1,213.30 | 295.69 |
| Ktor/Native | 12.18 | 46.81 | 154.10 | 7.99 | 1,225.80 | 257.61 |
| ktServe | 10.29 | 44.90 | 153.06 | 4.18 | 765.15 | 107.46 |
| Axum | 7.34 | 42.38 | 152.45 | 0.67 | 81.20 | 9.85 |

At 800/s the absolute fast and slow p99 gaps between ktServe and Axum were 2.96 ms and 2.52 ms. Both held the deadline p99 around 153 ms. Axum used about one ninth of ktServe's normalized frontend CPU and peak RSS was about one eleventh. The measurements establish fault handling under these hosted pilot conditions and give an end-to-end CPU baseline for profiling.

## Local Linux ARM64 cross-check at 800 requests/s

Two interleaved trials per frontend on Apple M3 Max in an OrbStack Linux ARM64 VM passed with 25,616 matching replies, zero missed starts or mismatches, and all 1,284 measured deadline sockets closed. This is a separate local sample. [Raw files and reproduction script](local-aarch64)

| Frontend | Fast p99 (ms) | Slow p99 (ms) | Deadline p99 (ms) | Disconnect p99 (ms) | CPU seconds per million matching replies | Peak RSS (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ktServe | 15.08 | 50.30 | 156.39 | 10.13 | 783.89 | 156.63 |
| Axum | 13.17 | 48.55 | 157.25 | 4.17 | 156.93 | 8.62 |

Table values are medians of each trial's reported statistic, including the trial-level peak RSS.
