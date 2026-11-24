# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/catalog/123` | 3 / 3 | 27,078 [26,640–27,273] | 524.0 [519.0–533.0] | 1,029.0 [999.0–1,031.0] | 1,247.0 [1,187.0–1,331.0] | 38.3 [38.2–38.8] | 17.4 [17.2–17.6] |
| ktor-jvm | `/catalog/123` | 3 / 3 | 35,835 [35,173–36,353] | 329.0 [324.0–330.0] | 1,616.0 [1,608.0–1,752.0] | 5,809.0 [5,169.0–7,194.0] | 93.4 [90.3–93.5] | 420.7 [371.1–428.8] |
| ktor-native | `/catalog/123` | 3 / 3 | 11,685 [9,958–12,124] | 1,190.0 [1,151.0–1,347.0] | 3,258.0 [2,915.0–4,230.0] | 4,313.0 [3,933.0–5,673.0] | 221.9 [217.7–255.6] | 156.8 [149.4–169.0] |
| rust-axum | `/catalog/123` | 3 / 3 | 103,827 [103,396–108,028] | 123.0 [121.0–129.0] | 350.0 [334.0–362.0] | 958.0 [916.0–1,007.0] | 20.3 [19.2–20.4] | 4.5 [4.4–4.6] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
