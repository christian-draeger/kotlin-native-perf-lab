# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 95,475 [93,803–96,524] | 153.0 [151.0–156.0] | 302.0 [299.0–308.0] | 341.0 [340.0–344.0] | 10.7 [10.6–10.9] | 15.8 [15.8–15.8] |
| ktor-jvm | `/json` | 3 / 3 | 14,818 [14,031–18,280] | 825.0 [673.0–890.0] | 3,330.0 [3,223.0–3,619.0] | 7,729.0 [7,126.0–8,440.0] | 240.0 [189.1–254.3] | 291.7 [283.0–296.0] |
| ktor-native | `/json` | 3 / 3 | 9,412 [9,378–9,426] | 1,575.0 [1,565.0–1,578.0] | 3,267.0 [3,206.0–3,273.0] | 5,107.0 [5,038.0–5,319.0] | 288.4 [287.3–289.4] | 155.4 [154.1–173.3] |
| rust-axum | `/json` | 3 / 3 | 133,022 [128,826–135,059] | 95.0 [92.0–103.0] | 261.0 [246.0–261.0] | 1,143.0 [984.0–1,187.0] | 11.7 [11.6–12.2] | 3.3 [3.3–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
