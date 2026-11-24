# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 145,967 [144,332–147,639] | 98.0 [97.0–100.0] | 193.0 [192.0–196.0] | 226.0 [225.0–230.0] | 7.0 [6.9–7.1] | 17.0 [16.9–17.0] |
| ktor-jvm | `/json` | 3 / 3 | 37,033 [33,281–41,785] | 319.0 [279.0–354.0] | 3,082.0 [3,067.0–3,770.0] | 7,600.0 [7,344.0–9,101.0] | 96.5 [83.7–108.5] | 353.0 [344.0–362.3] |
| ktor-native | `/json` | 3 / 3 | 16,730 [16,544–16,918] | 823.0 [822.0–828.0] | 3,491.0 [2,986.0–3,702.0] | 5,040.0 [4,554.0–5,418.0] | 169.2 [166.8–171.3] | 159.5 [156.7–173.7] |
| rust-axum | `/json` | 3 / 3 | 202,456 [199,940–204,209] | 85.0 [83.0–86.0] | 120.0 [119.0–122.0] | 532.0 [521.0–577.0] | 6.8 [6.8–7.0] | 4.2 [4.2–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
