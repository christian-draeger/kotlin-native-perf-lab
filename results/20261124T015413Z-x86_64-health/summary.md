# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 171,507 [169,868–172,074] | 84.0 [83.0–85.0] | 165.0 [164.0–166.0] | 186.0 [183.0–190.0] | 6.0 [5.9–6.0] | 16.9 [16.8–16.9] |
| ktor-jvm | `/health` | 3 / 3 | 40,914 [40,135–42,628] | 279.0 [274.0–287.0] | 3,900.0 [3,236.0–4,020.0] | 8,867.0 [7,995.0–9,481.0] | 86.7 [82.6–87.7] | 345.8 [338.1–356.9] |
| ktor-native | `/health` | 3 / 3 | 18,133 [17,822–18,290] | 764.0 [761.0–767.0] | 2,801.0 [2,663.0–3,265.0] | 4,255.0 [4,184.0–4,696.0] | 156.8 [155.7–158.8] | 150.9 [150.6–179.6] |
| rust-axum | `/health` | 3 / 3 | 207,378 [207,173–208,384] | 83.0 [82.0–84.0] | 116.0 [116.0–119.0] | 633.0 [586.0–749.0] | 6.5 [6.5–6.7] | 4.1 [4.1–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
