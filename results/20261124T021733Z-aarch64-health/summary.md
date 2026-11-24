# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 104,988 [101,483–112,729] | 138.0 [126.0–144.0] | 272.0 [252.0–281.0] | 306.0 [285.0–316.0] | 9.7 [9.0–10.0] | 16.0 [15.9–16.0] |
| ktor-jvm | `/health` | 3 / 3 | 19,841 [16,670–19,854] | 622.0 [615.0–719.0] | 3,102.0 [2,617.0–3,892.0] | 7,350.0 [6,329.0–8,048.0] | 174.8 [173.3–214.0] | 289.0 [286.3–291.1] |
| ktor-native | `/health` | 3 / 3 | 10,583 [10,008–10,648] | 1,391.0 [1,365.0–1,461.0] | 3,103.0 [3,027.0–3,303.0] | 4,891.0 [4,839.0–5,209.0] | 257.1 [253.6–270.9] | 157.9 [145.8–178.7] |
| rust-axum | `/health` | 3 / 3 | 139,548 [138,745–140,004] | 89.0 [88.0–90.0] | 253.0 [247.0–286.0] | 1,323.0 [1,248.0–1,358.0] | 11.1 [11.0–11.1] | 3.3 [3.3–3.5] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
