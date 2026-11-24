# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 111,511 [102,449–114,897] | 127.0 [124.0–142.0] | 256.0 [249.0–280.0] | 287.0 [280.0–314.0] | 9.1 [8.8–9.9] | 15.9 [15.9–16.0] |
| ktor-jvm | `/health` | 3 / 3 | 19,173 [16,685–20,409] | 647.0 [602.0–732.0] | 2,665.0 [2,495.0–3,314.0] | 7,253.0 [6,261.0–7,462.0] | 179.2 [166.2–204.2] | 282.3 [281.2–283.5] |
| ktor-native | `/health` | 3 / 3 | 9,883 [9,795–9,937] | 1,488.0 [1,487.0–1,502.0] | 3,223.0 [3,111.0–3,372.0] | 5,099.0 [4,972.0–5,277.0] | 272.4 [268.8–272.5] | 152.4 [148.9–152.8] |
| rust-axum | `/health` | 3 / 3 | 128,187 [124,777–130,230] | 127.0 [115.0–131.0] | 230.0 [228.0–240.0] | 1,113.0 [971.0–1,161.0] | 11.1 [10.8–11.3] | 3.3 [3.2–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
