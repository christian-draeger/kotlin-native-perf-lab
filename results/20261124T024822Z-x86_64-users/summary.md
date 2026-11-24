# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 133,236 [133,148–135,613] | 109.0 [106.0–109.0] | 212.0 [207.0–212.0] | 240.0 [233.0–241.0] | 7.8 [7.6–7.8] | 17.0 [17.0–17.1] |
| ktor-jvm | `/users/123` | 3 / 3 | 41,939 [37,624–44,219] | 281.0 [265.0–314.0] | 2,846.0 [2,627.0–3,193.0] | 7,811.0 [7,000.0–8,122.0] | 84.1 [78.6–94.2] | 354.4 [348.0–356.3] |
| ktor-native | `/users/123` | 3 / 3 | 17,309 [17,170–17,446] | 807.0 [797.0–811.0] | 2,843.0 [2,386.0–2,857.0] | 4,168.0 [3,684.0–4,315.0] | 163.9 [163.0–164.2] | 158.4 [156.5–170.6] |
| rust-axum | `/users/123` | 3 / 3 | 203,517 [203,054–205,056] | 83.0 [83.0–85.0] | 119.0 [119.0–120.0] | 557.0 [494.0–564.0] | 6.9 [6.9–7.0] | 4.2 [4.1–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
