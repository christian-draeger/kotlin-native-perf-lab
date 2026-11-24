# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 97,148 [93,103–97,160] | 152.0 [152.0–158.0] | 298.0 [297.0–311.0] | 324.0 [324.0–340.0] | 10.5 [10.5–10.9] | 15.9 [15.9–15.9] |
| ktor-jvm | `/json` | 3 / 3 | 19,873 [18,419–20,732] | 621.0 [581.0–672.0] | 2,908.0 [2,865.0–2,930.0] | 7,086.0 [7,055.0–7,266.0] | 172.2 [164.8–182.8] | 288.9 [284.4–291.4] |
| ktor-native | `/json` | 3 / 3 | 10,480 [10,471–10,512] | 1,412.0 [1,401.0–1,412.0] | 2,953.0 [2,936.0–2,955.0] | 4,559.0 [4,365.0–4,578.0] | 258.8 [258.0–259.2] | 158.4 [157.0–163.7] |
| rust-axum | `/json` | 3 / 3 | 138,747 [138,708–139,461] | 88.0 [88.0–91.0] | 235.0 [229.0–247.0] | 1,064.0 [969.0–1,114.0] | 11.3 [11.3–11.4] | 3.3 [3.2–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
