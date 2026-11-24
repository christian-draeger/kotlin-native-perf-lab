# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 114,465 [111,620–117,081] | 124.0 [121.0–129.0] | 249.0 [243.0–255.0] | 281.0 [271.0–293.0] | 8.9 [8.7–9.1] | 15.8 [15.8–15.8] |
| ktor-jvm | `/health` | 3 / 3 | 18,317 [18,247–18,471] | 666.0 [657.0–676.0] | 3,412.0 [2,997.0–3,478.0] | 7,658.0 [7,445.0–8,513.0] | 188.2 [188.0–190.3] | 286.6 [284.1–291.2] |
| ktor-native | `/health` | 3 / 3 | 10,359 [10,305–10,391] | 1,431.0 [1,424.0–1,435.0] | 3,028.0 [3,006.0–3,211.0] | 4,872.0 [4,808.0–5,317.0] | 262.1 [260.5–262.6] | 155.0 [151.4–174.9] |
| rust-axum | `/health` | 3 / 3 | 138,826 [138,420–139,628] | 89.0 [89.0–89.0] | 245.0 [245.0–245.0] | 1,194.0 [1,126.0–1,206.0] | 11.3 [11.2–11.3] | 3.3 [3.2–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
