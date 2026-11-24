# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 114,017 [113,759–116,740] | 126.0 [122.0–127.0] | 250.0 [243.0–250.0] | 291.0 [279.0–291.0] | 8.9 [8.7–8.9] | 15.8 [15.8–15.8] |
| ktor-jvm | `/health` | 3 / 3 | 16,417 [15,843–17,941] | 768.0 [695.0–789.0] | 2,963.0 [2,612.0–3,162.0] | 7,977.0 [7,148.0–8,182.0] | 211.8 [190.8–219.5] | 286.5 [277.8–291.4] |
| ktor-native | `/health` | 3 / 3 | 9,449 [9,400–10,181] | 1,553.0 [1,446.0–1,577.0] | 3,241.0 [3,194.0–3,271.0] | 4,979.0 [4,927.0–5,151.0] | 284.7 [265.3–287.9] | 147.8 [137.0–155.4] |
| rust-axum | `/health` | 3 / 3 | 138,505 [135,634–138,535] | 90.0 [90.0–98.0] | 243.0 [242.0–256.0] | 1,190.0 [1,176.0–1,193.0] | 11.1 [11.0–11.1] | 3.3 [3.3–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
