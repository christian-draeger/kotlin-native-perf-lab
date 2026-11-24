# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 134,386 [132,175–134,785] | 108.0 [107.0–110.0] | 210.0 [209.0–213.0] | 239.0 [239.0–243.0] | 7.7 [7.7–7.9] | 16.7 [16.6–16.7] |
| ktor-jvm | `/users/123` | 3 / 3 | 40,538 [38,828–40,969] | 293.0 [287.0–298.0] | 2,965.0 [2,824.0–3,361.0] | 7,872.0 [7,519.0–8,052.0] | 86.6 [86.0–91.8] | 352.5 [345.6–358.8] |
| ktor-native | `/users/123` | 3 / 3 | 16,519 [16,488–16,548] | 824.0 [822.0–830.0] | 3,740.0 [3,569.0–3,905.0] | 5,292.0 [5,085.0–5,426.0] | 171.1 [170.2–171.1] | 153.8 [149.9–156.6] |
| rust-axum | `/users/123` | 3 / 3 | 200,432 [198,705–201,447] | 83.0 [83.0–84.0] | 125.0 [125.0–129.0] | 551.0 [546.0–626.0] | 7.2 [7.1–7.2] | 4.2 [4.2–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
