# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/users/123` | 3 / 3 | 133,674 [132,199–135,628] | 108.0 [107.0–109.0] | 212.0 [208.0–213.0] | 240.0 [235.0–282.0] | 7.8 [7.6–7.8] | 16.8 [16.8–16.8] |
| ktor-jvm | `/users/123` | 3 / 3 | 39,448 [38,002–42,789] | 302.0 [279.0–309.0] | 2,832.0 [2,596.0–2,890.0] | 7,412.0 [7,195.0–7,464.0] | 89.7 [81.4–93.7] | 362.7 [358.1–367.3] |
| ktor-native | `/users/123` | 3 / 3 | 16,739 [16,661–16,815] | 820.0 [815.0–828.0] | 3,591.0 [3,359.0–3,605.0] | 5,263.0 [5,000.0–5,311.0] | 169.4 [168.3–170.3] | 164.5 [159.6–172.7] |
| rust-axum | `/users/123` | 3 / 3 | 200,294 [200,278–201,935] | 83.0 [83.0–84.0] | 123.0 [120.0–126.0] | 479.0 [448.0–606.0] | 7.1 [7.0–7.1] | 4.2 [4.1–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
