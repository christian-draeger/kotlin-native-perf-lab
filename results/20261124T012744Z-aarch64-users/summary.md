# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 87,660 [85,578–89,493] | 167.0 [164.0–172.0] | 332.0 [325.0–340.0] | 363.0 [356.0–375.0] | 11.7 [11.5–12.0] | 15.7 [15.7–15.7] |
| ktor-jvm | `/users/123` | 3 / 3 | 15,006 [14,816–15,129] | 838.0 [827.0–863.0] | 3,046.0 [2,999.0–3,285.0] | 6,838.0 [6,829.0–7,112.0] | 230.8 [230.5–234.6] | 286.5 [284.1–287.8] |
| ktor-native | `/users/123` | 3 / 3 | 8,260 [8,199–8,729] | 1,790.0 [1,689.0–1,809.0] | 3,622.0 [3,454.0–3,709.0] | 5,867.0 [5,825.0–6,093.0] | 323.1 [308.9–329.7] | 171.7 [167.0–183.9] |
| rust-axum | `/users/123` | 3 / 3 | 124,552 [122,215–124,684] | 127.0 [121.0–129.0] | 233.0 [233.0–239.0] | 818.0 [804.0–989.0] | 11.8 [11.6–11.9] | 3.3 [3.2–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
