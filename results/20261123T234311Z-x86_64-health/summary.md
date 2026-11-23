# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 83,274 [82,429–89,077] | 155.0 [144.0–157.0] | 307.0 [295.0–309.0] | 478.0 [456.0–482.0] | 12.2 [11.4–12.3] | 16.7 [16.6–16.7] |
| ktor-jvm | `/health` | 3 / 3 | 16,851 [16,074–17,300] | 684.0 [678.0–708.0] | 3,167.0 [2,708.0–3,380.0] | 7,681.0 [6,612.0–8,056.0] | 204.4 [201.3–217.3] | 298.1 [290.9–309.9] |
| ktor-native | `/health` | 3 / 3 | 9,331 [9,232–9,352] | 1,598.0 [1,590.0–1,616.0] | 3,071.0 [3,068.0–3,184.0] | 4,886.0 [4,866.0–4,945.0] | 302.2 [301.2–308.6] | 146.9 [145.5–164.2] |
| rust-axum | `/health` | 3 / 3 | 100,354 [100,250–101,342] | 147.0 [146.0–151.0] | 265.0 [264.0–267.0] | 678.0 [613.0–694.0] | 15.6 [15.6–15.8] | 4.1 [4.0–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
