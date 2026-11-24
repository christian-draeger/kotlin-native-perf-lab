# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 91,244 [90,652–95,800] | 162.0 [154.0–163.0] | 317.0 [304.0–320.0] | 345.0 [331.0–347.0] | 11.1 [10.6–11.2] | 15.8 [15.8–15.8] |
| ktor-jvm | `/json` | 3 / 3 | 19,098 [17,990–21,046] | 646.0 [579.0–676.0] | 2,899.0 [2,605.0–3,562.0] | 7,049.0 [6,948.0–8,160.0] | 181.1 [161.8–195.3] | 292.9 [291.6–296.0] |
| ktor-native | `/json` | 3 / 3 | 9,957 [9,929–10,218] | 1,476.0 [1,435.0–1,495.0] | 3,100.0 [3,001.0–3,236.0] | 4,923.0 [4,743.0–5,022.0] | 271.8 [265.0–272.5] | 157.6 [155.0–170.6] |
| rust-axum | `/json` | 3 / 3 | 139,860 [136,261–140,840] | 89.0 [87.0–99.0] | 236.0 [234.0–239.0] | 1,038.0 [1,031.0–1,093.0] | 11.1 [10.8–11.2] | 3.4 [3.3–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
