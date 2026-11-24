# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 108,233 [105,966–113,005] | 135.0 [127.0–142.0] | 252.0 [241.0–262.0] | 303.0 [303.0–321.0] | 9.3 [8.9–9.5] | 16.8 [16.7–16.9] |
| ktor-jvm | `/users/123` | 3 / 3 | 44,530 [40,809–47,171] | 259.0 [252.0–284.0] | 1,521.0 [1,370.0–2,139.0] | 5,062.0 [4,171.0–6,348.0] | 70.3 [66.9–80.5] | 420.1 [348.0–427.0] |
| ktor-native | `/users/123` | 3 / 3 | 16,096 [15,896–16,232] | 896.0 [883.0–913.0] | 2,404.0 [2,397.0–2,409.0] | 3,956.0 [3,734.0–4,069.0] | 162.1 [161.6–165.0] | 166.4 [139.1–200.9] |
| rust-axum | `/users/123` | 3 / 3 | 176,839 [174,537–178,414] | 78.0 [77.0–78.0] | 160.0 [149.0–161.0] | 384.0 [353.0–502.0] | 8.7 [8.6–8.8] | 4.2 [4.1–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
