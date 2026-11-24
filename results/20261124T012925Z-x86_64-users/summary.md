# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 104,661 [102,998–110,020] | 139.0 [133.0–142.0] | 262.0 [252.0–272.0] | 321.0 [303.0–374.0] | 9.7 [9.2–9.9] | 16.7 [16.7–16.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 43,460 [41,000–45,236] | 271.0 [262.0–284.0] | 1,437.0 [1,349.0–1,569.0] | 5,239.0 [4,543.0–5,240.0] | 73.3 [69.8–78.0] | 359.2 [346.2–418.0] |
| ktor-native | `/users/123` | 3 / 3 | 15,663 [15,563–15,747] | 923.0 [921.0–927.0] | 2,572.0 [2,346.0–2,669.0] | 4,199.0 [3,705.0–4,271.0] | 167.6 [167.1–170.4] | 175.5 [150.7–197.2] |
| rust-axum | `/users/123` | 3 / 3 | 170,913 [169,675–171,367] | 80.0 [79.0–80.0] | 166.0 [165.0–171.0] | 431.0 [404.0–472.0] | 8.9 [8.8–9.0] | 4.2 [4.2–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
