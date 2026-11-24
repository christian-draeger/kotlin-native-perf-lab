# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 86,253 [84,501–87,197] | 170.0 [169.0–173.0] | 334.0 [331.0–340.0] | 364.0 [360.0–377.0] | 11.9 [11.7–12.1] | 15.8 [15.7–15.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 16,569 [16,498–16,953] | 757.0 [737.0–767.0] | 3,013.0 [2,850.0–3,260.0] | 6,814.0 [6,642.0–8,451.0] | 211.0 [205.8–212.9] | 285.8 [284.5–291.7] |
| ktor-native | `/users/123` | 3 / 3 | 8,967 [8,669–8,992] | 1,652.0 [1,639.0–1,698.0] | 3,557.0 [3,541.0–3,629.0] | 5,702.0 [5,339.0–5,741.0] | 302.9 [300.1–312.6] | 158.2 [141.5–187.5] |
| rust-axum | `/users/123` | 3 / 3 | 127,982 [127,196–130,489] | 104.0 [98.0–105.0] | 239.0 [238.0–241.0] | 916.0 [854.0–975.0] | 12.2 [12.1–12.4] | 3.3 [3.2–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
