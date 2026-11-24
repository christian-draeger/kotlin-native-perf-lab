# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 110,140 [107,148–111,975] | 131.0 [128.0–133.0] | 258.0 [255.0–266.0] | 296.0 [282.0–299.0] | 9.2 [9.1–9.5] | 15.8 [15.8–15.8] |
| ktor-jvm | `/health` | 3 / 3 | 18,040 [17,875–20,745] | 683.0 [599.0–701.0] | 2,573.0 [2,278.0–3,176.0] | 5,727.0 [5,670.0–6,962.0] | 193.6 [163.5–193.7] | 292.6 [283.6–294.5] |
| ktor-native | `/health` | 3 / 3 | 9,863 [9,858–10,059] | 1,482.0 [1,456.0–1,503.0] | 3,116.0 [3,113.0–3,405.0] | 4,939.0 [4,924.0–5,318.0] | 269.7 [268.8–276.1] | 148.1 [146.7–155.8] |
| rust-axum | `/health` | 3 / 3 | 136,241 [135,467–136,972] | 94.0 [91.0–97.0] | 266.0 [247.0–268.0] | 1,209.0 [1,158.0–1,233.0] | 11.4 [11.1–11.5] | 3.3 [3.3–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
