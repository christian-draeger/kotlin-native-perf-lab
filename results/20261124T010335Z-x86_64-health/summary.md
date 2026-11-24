# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 170,821 [170,207–172,026] | 84.0 [84.0–84.0] | 165.0 [165.0–166.0] | 182.0 [182.0–185.0] | 6.0 [5.9–6.0] | 16.8 [16.8–16.8] |
| ktor-jvm | `/health` | 3 / 3 | 41,607 [37,604–45,015] | 282.0 [263.0–316.0] | 2,998.0 [2,739.0–3,241.0] | 7,531.0 [7,329.0–7,873.0] | 85.3 [76.8–95.3] | 349.1 [305.2–350.7] |
| ktor-native | `/health` | 3 / 3 | 17,884 [17,753–18,063] | 770.0 [769.0–771.0] | 2,821.0 [2,735.0–3,271.0] | 4,236.0 [4,135.0–4,710.0] | 158.7 [157.5–159.5] | 157.2 [148.7–158.2] |
| rust-axum | `/health` | 3 / 3 | 209,606 [209,387–210,665] | 83.0 [82.0–83.0] | 115.0 [114.0–116.0] | 625.0 [613.0–662.0] | 6.5 [6.5–6.5] | 4.2 [4.2–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
