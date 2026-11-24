# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 74,597 [71,211–75,476] | 193.0 [170.0–216.0] | 338.0 [338.0–348.0] | 531.0 [502.0–541.0] | 13.6 [13.4–14.2] | 16.7 [16.6–16.7] |
| ktor-jvm | `/json` | 3 / 3 | 14,330 [12,101–14,888] | 789.0 [781.0–1,005.0] | 4,306.0 [3,562.0–4,381.0] | 9,117.0 [7,762.0–9,922.0] | 252.0 [238.9–301.9] | 281.1 [280.5–293.3] |
| ktor-native | `/json` | 3 / 3 | 8,968 [8,924–8,996] | 1,668.0 [1,659.0–1,673.0] | 3,134.0 [3,110.0–3,198.0] | 4,736.0 [4,618.0–4,912.0] | 316.4 [315.6–316.8] | 158.1 [151.1–160.1] |
| rust-axum | `/json` | 3 / 3 | 97,482 [97,329–98,103] | 148.0 [146.0–149.0] | 276.0 [276.0–295.0] | 676.0 [675.0–765.0] | 16.6 [16.4–16.8] | 4.1 [4.1–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
