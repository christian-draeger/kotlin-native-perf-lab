# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 109,754 [108,398–111,392] | 126.0 [125.0–133.0] | 256.0 [248.0–261.0] | 315.0 [306.0–409.0] | 9.3 [9.1–9.4] | 16.9 [16.9–16.9] |
| ktor-jvm | `/health` | 3 / 3 | 45,793 [45,018–46,367] | 252.0 [249.0–259.0] | 1,795.0 [1,239.0–1,911.0] | 6,168.0 [4,343.0–6,219.0] | 68.8 [66.8–71.1] | 356.5 [353.9–357.4] |
| ktor-native | `/health` | 3 / 3 | 15,622 [14,868–15,695] | 917.0 [913.0–958.0] | 2,875.0 [2,724.0–2,994.0] | 4,457.0 [4,118.0–4,679.0] | 166.8 [163.9–176.2] | 148.4 [136.4–186.7] |
| rust-axum | `/health` | 3 / 3 | 172,447 [169,088–173,041] | 76.0 [75.0–77.0] | 170.0 [169.0–175.0] | 403.0 [396.0–441.0] | 8.8 [8.7–8.8] | 4.2 [4.2–4.2] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
