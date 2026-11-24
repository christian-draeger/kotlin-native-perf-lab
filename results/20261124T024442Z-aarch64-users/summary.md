# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 84,097 [79,753–84,612] | 175.0 [174.0–185.0] | 341.0 [340.0–357.0] | 381.0 [370.0–401.0] | 12.1 [12.0–12.8] | 15.8 [15.8–15.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 20,993 [20,186–21,244] | 592.0 [557.0–601.0] | 3,286.0 [2,464.0–3,325.0] | 7,482.0 [6,537.0–8,543.0] | 163.4 [160.9–170.0] | 290.5 [285.4–299.2] |
| ktor-native | `/users/123` | 3 / 3 | 10,561 [10,553–10,576] | 1,403.0 [1,385.0–1,406.0] | 2,866.0 [2,850.0–2,930.0] | 4,421.0 [4,301.0–4,670.0] | 256.5 [255.8–256.8] | 174.3 [166.0–177.7] |
| rust-axum | `/users/123` | 3 / 3 | 138,134 [135,479–140,573] | 95.0 [88.0–105.0] | 235.0 [234.0–258.0] | 1,093.0 [1,024.0–1,096.0] | 10.8 [10.7–11.3] | 3.3 [3.2–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
