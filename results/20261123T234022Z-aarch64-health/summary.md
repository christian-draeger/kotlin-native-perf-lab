# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 109,850 [109,777–110,162] | 129.0 [129.0–130.0] | 258.0 [258.0–258.0] | 296.0 [292.0–302.0] | 9.2 [9.2–9.3] | 15.9 [15.8–15.9] |
| ktor-jvm | `/health` | 3 / 3 | 18,154 [16,815–19,979] | 673.0 [617.0–741.0] | 2,997.0 [2,458.0–3,179.0] | 6,441.0 [6,208.0–8,311.0] | 189.6 [170.3–207.4] | 285.0 [284.5–298.9] |
| ktor-native | `/health` | 3 / 3 | 9,873 [9,827–9,951] | 1,492.0 [1,474.0–1,496.0] | 3,162.0 [3,098.0–3,195.0] | 4,908.0 [4,831.0–4,999.0] | 274.6 [271.7–275.4] | 152.5 [145.7–156.1] |
| rust-axum | `/health` | 3 / 3 | 131,970 [131,180–133,489] | 95.0 [95.0–108.0] | 246.0 [234.0–265.0] | 1,104.0 [1,089.0–1,276.0] | 11.8 [11.0–11.9] | 3.3 [3.3–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
