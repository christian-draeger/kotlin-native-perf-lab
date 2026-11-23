# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 94,372 [91,597–94,673] | 156.0 [155.0–161.0] | 306.0 [305.0–315.0] | 338.0 [338.0–347.0] | 10.7 [10.7–11.1] | 15.8 [15.8–15.9] |
| ktor-jvm | `/json` | 3 / 3 | 18,599 [17,867–19,822] | 653.0 [622.0–691.0] | 3,009.0 [2,877.0–3,191.0] | 7,381.0 [7,096.0–7,702.0] | 185.9 [172.3–195.9] | 293.1 [290.7–293.4] |
| ktor-native | `/json` | 3 / 3 | 10,013 [9,736–10,037] | 1,482.0 [1,475.0–1,506.0] | 3,054.0 [3,026.0–3,187.0] | 4,674.0 [4,646.0–4,878.0] | 271.8 [269.1–279.1] | 159.1 [151.6–168.3] |
| rust-axum | `/json` | 3 / 3 | 133,225 [132,897–134,307] | 93.0 [93.0–95.0] | 256.0 [250.0–260.0] | 1,106.0 [1,073.0–1,156.0] | 12.0 [11.9–12.2] | 3.4 [3.4–3.5] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
