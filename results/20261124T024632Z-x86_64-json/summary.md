# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 100,882 [99,113–101,691] | 146.0 [131.0–159.0] | 256.0 [255.0–257.0] | 353.0 [351.0–368.0] | 10.1 [10.0–10.2] | 16.9 [16.9–17.0] |
| ktor-jvm | `/json` | 3 / 3 | 28,030 [24,874–28,919] | 410.0 [406.0–472.0] | 2,003.0 [1,866.0–2,293.0] | 6,269.0 [5,667.0–7,047.0] | 121.6 [115.2–138.5] | 319.5 [310.0–357.4] |
| ktor-native | `/json` | 3 / 3 | 12,613 [12,583–12,781] | 1,172.0 [1,159.0–1,172.0] | 2,530.0 [2,408.0–2,635.0] | 3,911.0 [3,863.0–4,126.0] | 221.9 [220.8–223.2] | 146.5 [138.7–167.4] |
| rust-axum | `/json` | 3 / 3 | 141,329 [139,711–142,617] | 106.0 [105.0–108.0] | 174.0 [170.0–175.0] | 451.0 [373.0–464.0] | 10.6 [10.5–10.6] | 4.2 [4.1–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
