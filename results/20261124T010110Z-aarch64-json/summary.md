# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 97,313 [95,791–97,425] | 150.0 [150.0–152.0] | 297.0 [294.0–300.0] | 342.0 [333.0–346.0] | 10.5 [10.4–10.6] | 15.9 [15.9–15.9] |
| ktor-jvm | `/json` | 3 / 3 | 17,450 [17,328–18,448] | 713.0 [673.0–721.0] | 3,025.0 [2,780.0–3,074.0] | 6,889.0 [6,603.0–6,965.0] | 200.4 [186.1–200.5] | 282.6 [280.8–289.0] |
| ktor-native | `/json` | 3 / 3 | 9,648 [9,394–9,650] | 1,536.0 [1,519.0–1,571.0] | 3,172.0 [3,169.0–3,291.0] | 4,962.0 [4,894.0–5,527.0] | 281.6 [281.4–290.2] | 158.2 [148.2–181.0] |
| rust-axum | `/json` | 3 / 3 | 137,190 [136,940–137,787] | 93.0 [93.0–94.0] | 242.0 [238.0–245.0] | 1,120.0 [1,027.0–1,154.0] | 11.3 [11.0–11.4] | 3.3 [3.2–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
