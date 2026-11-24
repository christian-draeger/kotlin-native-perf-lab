# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 90,253 [87,692–91,626] | 162.0 [159.0–165.0] | 319.0 [313.0–325.0] | 362.0 [361.0–385.0] | 11.3 [11.1–11.6] | 15.8 [15.8–15.8] |
| ktor-jvm | `/json` | 3 / 3 | 17,585 [17,098–17,831] | 694.0 [687.0–717.0] | 3,081.0 [2,884.0–3,516.0] | 6,815.0 [6,735.0–8,414.0] | 197.7 [191.1–202.6] | 287.0 [286.8–290.7] |
| ktor-native | `/json` | 3 / 3 | 8,997 [8,950–9,375] | 1,633.0 [1,577.0–1,637.0] | 3,415.0 [3,385.0–3,490.0] | 5,380.0 [5,237.0–5,569.0] | 301.9 [288.1–302.1] | 166.4 [155.0–169.3] |
| rust-axum | `/json` | 3 / 3 | 129,559 [127,360–132,395] | 97.0 [96.0–102.0] | 261.0 [251.0–261.0] | 1,117.0 [1,098.0–1,184.0] | 12.2 [11.9–12.2] | 3.3 [3.3–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
