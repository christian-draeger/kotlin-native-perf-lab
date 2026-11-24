# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 86,020 [85,076–86,881] | 170.0 [169.0–172.0] | 333.0 [332.0–339.0] | 372.0 [366.0–373.0] | 11.9 [11.8–12.0] | 15.7 [15.7–15.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 16,289 [15,526–16,428] | 767.0 [757.0–813.0] | 3,095.0 [3,039.0–3,161.0] | 6,582.0 [6,393.0–7,569.0] | 212.9 [211.9–225.3] | 283.0 [281.0–288.2] |
| ktor-native | `/users/123` | 3 / 3 | 8,932 [8,736–9,021] | 1,667.0 [1,628.0–1,702.0] | 3,443.0 [3,367.0–3,465.0] | 5,459.0 [5,218.0–5,474.0] | 300.2 [298.6–309.1] | 153.7 [152.5–157.5] |
| rust-axum | `/users/123` | 3 / 3 | 126,299 [123,738–127,827] | 115.0 [109.0–125.0] | 224.0 [220.0–232.0] | 781.0 [760.0–905.0] | 11.8 [11.6–12.0] | 3.3 [3.3–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
