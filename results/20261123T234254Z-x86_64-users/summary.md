# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 136,372 [135,972–137,017] | 107.0 [105.0–107.0] | 206.0 [204.0–208.0] | 231.0 [231.0–232.0] | 7.6 [7.5–7.6] | 16.7 [16.7–16.7] |
| ktor-jvm | `/users/123` | 3 / 3 | 38,595 [36,066–39,068] | 307.0 [303.0–330.0] | 2,880.0 [2,858.0–3,284.0] | 7,688.0 [7,080.0–8,475.0] | 92.2 [90.8–98.4] | 345.9 [341.3–350.6] |
| ktor-native | `/users/123` | 3 / 3 | 16,982 [16,958–17,019] | 816.0 [812.0–818.0] | 3,040.0 [2,788.0–3,393.0] | 4,522.0 [4,253.0–4,862.0] | 167.8 [167.2–167.9] | 158.7 [158.0–158.8] |
| rust-axum | `/users/123` | 3 / 3 | 201,543 [200,707–202,921] | 86.0 [85.0–86.0] | 119.0 [119.0–121.0] | 493.0 [432.0–559.0] | 7.0 [6.9–7.0] | 4.2 [4.2–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
