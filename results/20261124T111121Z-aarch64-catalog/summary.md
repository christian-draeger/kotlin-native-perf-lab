# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/catalog/123` | 3 / 3 | 11,114 [11,018–11,236] | 1,330.0 [1,316.0–1,346.0] | 2,631.0 [2,607.0–2,654.0] | 2,793.0 [2,754.0–2,821.0] | 93.5 [92.5–94.5] | 16.3 [16.3–16.3] |
| ktor-jvm | `/catalog/123` | 3 / 3 | 14,553 [14,393–15,079] | 884.0 [843.0–894.0] | 3,100.0 [2,975.0–3,289.0] | 6,922.0 [6,420.0–7,960.0] | 241.2 [231.9–243.8] | 341.4 [340.0–342.6] |
| ktor-native | `/catalog/123` | 3 / 3 | 6,418 [6,382–6,438] | 2,252.0 [2,250.0–2,257.0] | 5,163.0 [5,033.0–5,230.0] | 6,722.0 [6,549.0–6,848.0] | 409.5 [408.5–410.8] | 165.3 [146.0–172.7] |
| rust-axum | `/catalog/123` | 3 / 3 | 89,094 [88,143–89,248] | 142.0 [142.0–143.0] | 379.0 [372.0–393.0] | 626.0 [608.0–709.0] | 26.5 [26.5–27.2] | 3.6 [3.6–3.6] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
