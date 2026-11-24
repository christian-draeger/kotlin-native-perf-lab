# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 145,686 [144,794–145,913] | 98.0 [98.0–99.0] | 193.0 [193.0–196.0] | 226.0 [225.0–227.0] | 7.0 [7.0–7.1] | 17.0 [17.0–17.0] |
| ktor-jvm | `/json` | 3 / 3 | 39,233 [37,416–42,580] | 305.0 [280.0–311.0] | 3,147.0 [2,638.0–3,236.0] | 7,893.0 [7,035.0–7,981.0] | 90.6 [82.2–95.6] | 350.1 [294.2–377.6] |
| ktor-native | `/json` | 3 / 3 | 17,175 [16,883–17,775] | 798.0 [789.0–803.0] | 3,100.0 [2,782.0–3,934.0] | 4,609.0 [4,248.0–5,708.0] | 165.5 [160.4–166.8] | 166.7 [157.3–185.6] |
| rust-axum | `/json` | 3 / 3 | 204,602 [204,026–204,897] | 84.0 [83.0–84.0] | 119.0 [117.0–119.0] | 539.0 [507.0–540.0] | 6.8 [6.7–6.9] | 4.1 [4.1–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
