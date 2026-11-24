# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 125,701 [122,205–127,416] | 113.0 [111.0–116.0] | 221.0 [215.0–226.0] | 280.0 [279.0–302.0] | 8.1 [8.0–8.3] | 17.0 [17.0–17.1] |
| ktor-jvm | `/health` | 3 / 3 | 37,475 [32,440–39,994] | 315.0 [296.0–368.0] | 2,932.0 [2,813.0–3,043.0] | 7,270.0 [7,230.0–7,990.0] | 93.3 [85.3–108.4] | 364.7 [355.8–420.0] |
| ktor-native | `/health` | 3 / 3 | 13,128 [12,888–13,224] | 1,076.0 [1,075.0–1,097.0] | 3,383.0 [3,191.0–3,543.0] | 5,193.0 [4,999.0–5,552.0] | 203.4 [202.0–207.2] | 148.7 [143.4–160.5] |
| rust-axum | `/health` | 3 / 3 | 156,850 [153,419–158,048] | 90.0 [87.0–92.0] | 236.0 [195.0–393.0] | 1,148.0 [934.0–1,542.0] | 8.9 [8.9–9.0] | 4.2 [4.2–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
