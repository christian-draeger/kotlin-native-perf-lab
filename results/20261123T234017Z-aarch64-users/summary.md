# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 86,472 [85,325–86,832] | 171.0 [170.0–172.0] | 333.0 [332.0–335.0] | 363.0 [361.0–371.0] | 11.8 [11.8–11.9] | 15.8 [15.6–15.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 19,697 [19,637–21,592] | 632.0 [567.0–633.0] | 2,720.0 [2,507.0–2,782.0] | 6,929.0 [6,814.0–7,271.0] | 174.8 [156.9–174.9] | 292.5 [288.1–294.1] |
| ktor-native | `/users/123` | 3 / 3 | 10,009 [9,894–10,149] | 1,473.0 [1,448.0–1,486.0] | 3,077.0 [3,056.0–3,207.0] | 4,938.0 [4,870.0–5,032.0] | 271.6 [268.0–273.5] | 155.9 [154.9–163.9] |
| rust-axum | `/users/123` | 3 / 3 | 135,305 [134,921–135,587] | 93.0 [92.0–93.0] | 228.0 [227.0–238.0] | 852.0 [830.0–928.0] | 12.0 [11.9–12.0] | 3.3 [3.3–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
