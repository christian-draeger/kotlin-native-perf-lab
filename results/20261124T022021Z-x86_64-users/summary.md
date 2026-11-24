# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 65,765 [65,487–66,788] | 239.0 [233.0–243.0] | 383.0 [376.0–388.0] | 556.0 [547.0–570.0] | 15.4 [15.2–15.5] | 16.8 [16.8–16.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 14,788 [13,634–15,959] | 789.0 [739.0–865.0] | 3,638.0 [3,350.0–4,020.0] | 8,598.0 [7,644.0–8,849.0] | 243.0 [214.5–264.4] | 288.9 [287.9–293.7] |
| ktor-native | `/users/123` | 3 / 3 | 8,825 [8,691–8,852] | 1,691.0 [1,680.0–1,715.0] | 3,305.0 [3,182.0–3,425.0] | 4,826.0 [4,820.0–5,452.0] | 321.6 [319.9–325.5] | 153.6 [147.5–166.8] |
| rust-axum | `/users/123` | 3 / 3 | 96,774 [96,767–97,330] | 145.0 [145.0–149.0] | 286.0 [283.0–286.0] | 711.0 [701.0–727.0] | 17.2 [17.2–17.3] | 4.1 [4.0–4.1] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
