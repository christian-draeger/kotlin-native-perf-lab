# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 144,807 [143,117–145,402] | 98.0 [98.0–100.0] | 195.0 [194.0–198.0] | 223.0 [222.0–224.0] | 7.1 [7.1–7.2] | 16.9 [16.8–16.9] |
| ktor-jvm | `/json` | 3 / 3 | 41,400 [37,376–42,383] | 287.0 [279.0–319.0] | 2,731.0 [2,670.0–2,822.0] | 7,172.0 [6,913.0–7,365.0] | 84.2 [82.6–95.0] | 355.0 [354.4–415.4] |
| ktor-native | `/json` | 3 / 3 | 17,016 [17,002–17,036] | 810.0 [810.0–823.0] | 2,871.0 [2,693.0–3,245.0] | 4,349.0 [4,049.0–4,646.0] | 168.2 [166.2–168.4] | 148.0 [146.8–177.9] |
| rust-axum | `/json` | 3 / 3 | 202,112 [201,424–202,168] | 85.0 [84.0–86.0] | 121.0 [120.0–122.0] | 583.0 [518.0–600.0] | 6.9 [6.9–6.9] | 4.2 [4.1–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
