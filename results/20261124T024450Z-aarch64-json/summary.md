# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 90,521 [85,915–93,366] | 163.0 [157.0–171.0] | 318.0 [309.0–333.0] | 350.0 [343.0–374.0] | 11.2 [10.9–11.8] | 15.8 [15.8–15.8] |
| ktor-jvm | `/json` | 3 / 3 | 18,817 [17,050–19,215] | 649.0 [638.0–693.0] | 3,096.0 [3,060.0–3,806.0] | 8,144.0 [8,122.0–8,868.0] | 184.2 [175.6–202.3] | 287.3 [281.5–289.2] |
| ktor-native | `/json` | 3 / 3 | 9,980 [9,845–10,054] | 1,489.0 [1,473.0–1,490.0] | 3,027.0 [3,011.0–3,147.0] | 4,716.0 [4,566.0–4,785.0] | 270.8 [270.5–275.9] | 161.0 [155.4–166.8] |
| rust-axum | `/json` | 3 / 3 | 133,222 [128,504–137,248] | 95.0 [90.0–106.0] | 239.0 [233.0–245.0] | 1,032.0 [1,018.0–1,151.0] | 11.5 [11.4–11.9] | 3.3 [3.2–3.5] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
