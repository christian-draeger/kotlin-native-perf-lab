# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/json` | 3 / 3 | 146,106 [143,831–147,174] | 98.0 [98.0–101.0] | 195.0 [193.0–195.0] | 224.0 [222.0–227.0] | 7.0 [7.0–7.1] | 16.9 [16.9–17.2] |
| ktor-jvm | `/json` | 3 / 3 | 40,768 [36,599–41,608] | 285.0 [280.0–329.0] | 3,058.0 [2,899.0–4,458.0] | 7,600.0 [7,438.0–9,358.0] | 86.1 [84.4–97.9] | 344.1 [332.0–351.2] |
| ktor-native | `/json` | 3 / 3 | 17,258 [17,247–17,402] | 807.0 [803.0–812.0] | 2,712.0 [2,700.0–2,960.0] | 4,251.0 [4,057.0–4,364.0] | 165.4 [164.2–165.7] | 155.2 [150.7–176.4] |
| rust-axum | `/json` | 3 / 3 | 201,773 [201,072–202,201] | 85.0 [85.0–86.0] | 120.0 [120.0–122.0] | 594.0 [570.0–625.0] | 6.9 [6.8–6.9] | 4.3 [4.2–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
