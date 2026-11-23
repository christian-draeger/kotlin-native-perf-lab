# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 143,545 [142,226–148,605] | 99.0 [96.0–100.0] | 198.0 [190.0–200.0] | 246.0 [220.0–251.0] | 7.2 [7.0–7.3] | 16.8 [16.8–16.8] |
| ktor-jvm | `/json` | 3 / 3 | 37,648 [36,812–41,969] | 313.0 [288.0–315.0] | 2,978.0 [2,362.0–3,431.0] | 7,498.0 [6,197.0–8,852.0] | 94.3 [83.6–96.6] | 349.1 [348.2–355.4] |
| ktor-native | `/json` | 3 / 3 | 15,571 [15,551–16,158] | 841.0 [827.0–868.0] | 4,533.0 [4,340.0–5,081.0] | 6,099.0 [5,987.0–6,763.0] | 177.3 [172.6–179.6] | 156.6 [153.9–166.8] |
| rust-axum | `/json` | 3 / 3 | 200,566 [200,339–200,683] | 85.0 [85.0–86.0] | 124.0 [124.0–124.0] | 597.0 [485.0–604.0] | 7.0 [6.9–7.0] | 4.2 [4.2–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
