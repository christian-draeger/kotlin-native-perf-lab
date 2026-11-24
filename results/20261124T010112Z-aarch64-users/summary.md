# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 83,409 [82,218–85,829] | 177.0 [172.0–180.0] | 346.0 [338.0–352.0] | 376.0 [367.0–382.0] | 12.2 [11.9–12.4] | 15.7 [15.7–15.9] |
| ktor-jvm | `/users/123` | 3 / 3 | 18,309 [17,786–18,500] | 680.0 [675.0–686.0] | 2,911.0 [2,854.0–3,259.0] | 7,488.0 [6,786.0–7,600.0] | 190.7 [185.9–196.1] | 290.2 [288.1–294.7] |
| ktor-native | `/users/123` | 3 / 3 | 9,600 [9,586–9,676] | 1,534.0 [1,528.0–1,539.0] | 3,304.0 [3,280.0–3,305.0] | 5,097.0 [5,095.0–5,458.0] | 282.0 [279.2–283.3] | 155.0 [151.4–182.9] |
| rust-axum | `/users/123` | 3 / 3 | 134,156 [133,344–137,561] | 95.0 [94.0–95.0] | 240.0 [233.0–301.0] | 990.0 [955.0–1,437.0] | 11.8 [11.5–12.1] | 3.3 [3.3–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
