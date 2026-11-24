# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 106,742 [103,893–110,072] | 132.0 [120.0–140.0] | 240.0 [239.0–247.0] | 335.0 [331.0–342.0] | 9.5 [9.2–9.8] | 17.0 [16.8–17.0] |
| ktor-jvm | `/health` | 3 / 3 | 29,111 [27,191–29,151] | 399.0 [398.0–426.0] | 2,029.0 [1,974.0–2,141.0] | 5,976.0 [5,915.0–6,144.0] | 115.9 [114.6–125.3] | 310.5 [309.0–368.8] |
| ktor-native | `/health` | 3 / 3 | 13,105 [13,016–13,212] | 1,122.0 [1,117.0–1,126.0] | 2,516.0 [2,487.0–2,553.0] | 3,960.0 [3,932.0–4,320.0] | 213.9 [211.0–218.0] | 136.8 [134.6–179.7] |
| rust-axum | `/health` | 3 / 3 | 138,516 [137,540–138,614] | 103.0 [99.0–107.0] | 186.0 [176.0–205.0] | 551.0 [500.0–821.0] | 10.9 [10.6–10.9] | 4.2 [4.1–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
