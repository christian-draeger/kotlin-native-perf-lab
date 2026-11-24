# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 163,584 [161,203–164,557] | 88.0 [87.0–89.0] | 174.0 [172.0–176.0] | 193.0 [192.0–199.0] | 6.3 [6.2–6.4] | 17.1 [16.9–17.1] |
| ktor-jvm | `/health` | 3 / 3 | 39,415 [39,094–44,073] | 300.0 [263.0–303.0] | 3,210.0 [2,773.0–3,299.0] | 7,919.0 [6,975.0–8,374.0] | 89.5 [79.1–90.7] | 350.4 [310.9–354.1] |
| ktor-native | `/health` | 3 / 3 | 17,050 [16,979–17,777] | 792.0 [785.0–798.0] | 3,659.0 [2,837.0–3,736.0] | 5,103.0 [4,256.0–5,250.0] | 165.5 [160.3–165.6] | 149.1 [148.2–150.1] |
| rust-axum | `/health` | 3 / 3 | 203,604 [202,100–204,106] | 85.0 [83.0–85.0] | 121.0 [119.0–121.0] | 600.0 [566.0–665.0] | 6.8 [6.8–6.9] | 4.2 [4.1–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
