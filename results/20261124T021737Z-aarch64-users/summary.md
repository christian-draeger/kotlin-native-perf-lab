# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 85,674 [85,551–85,876] | 171.0 [170.0–172.0] | 334.0 [333.0–337.0] | 373.0 [369.0–373.0] | 11.9 [11.9–12.0] | 15.8 [15.8–15.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 17,400 [17,100–19,514] | 719.0 [641.0–730.0] | 2,865.0 [2,563.0–3,024.0] | 6,808.0 [6,787.0–7,497.0] | 199.2 [176.9–205.6] | 289.5 [282.3–298.9] |
| ktor-native | `/users/123` | 3 / 3 | 9,648 [9,430–9,704] | 1,524.0 [1,511.0–1,549.0] | 3,275.0 [3,226.0–3,420.0] | 5,236.0 [5,223.0–5,700.0] | 281.3 [278.0–288.0] | 172.3 [151.1–173.5] |
| rust-axum | `/users/123` | 3 / 3 | 133,471 [129,580–133,526] | 97.0 [95.0–97.0] | 226.0 [223.0–231.0] | 738.0 [715.0–807.0] | 12.1 [12.1–12.9] | 3.3 [3.3–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
