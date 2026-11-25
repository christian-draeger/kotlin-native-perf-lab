# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1; separate server and load CPU affinity sets on one host. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| axum-2-tokio-workers | `/json` | 3 / 3 | 263,369 [262,541–265,209] | 242.0 [241.0–242.0] | 350.0 [348.0–351.0] | 384.0 [380.0–403.0] | 7.4 [7.4–7.5] | 5.5 [5.2–5.5] |
| ktserve-1-worker | `/json` | 3 / 3 | 158,573 [157,894–159,872] | 397.0 [393.0–399.0] | 459.0 [457.0–468.0] | 675.0 [634.0–711.0] | 6.5 [6.4–6.5] | 17.9 [17.7–18.0] |
| ktserve-2-workers | `/json` | 3 / 3 | 273,898 [273,870–279,176] | 192.0 [187.0–206.0] | 987.0 [846.0–1,013.0] | 1,889.0 [1,605.0–2,039.0] | 7.0 [6.9–7.0] | 21.3 [20.8–21.8] |
| micronaut-1-io-thread | `/json` | 3 / 3 | 127,005 [126,967–127,480] | 492.0 [492.0–493.0] | 589.0 [584.0–593.0] | 800.0 [698.0–927.0] | 8.9 [8.9–8.9] | 343.2 [341.6–344.2] |
| micronaut-2-io-threads | `/json` | 3 / 3 | 220,541 [212,709–231,082] | 250.0 [247.0–266.0] | 1,383.0 [798.0–1,405.0] | 3,115.0 [3,018.0–3,171.0] | 9.0 [8.6–9.3] | 344.1 [341.4–346.1] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
load errors, or unmet warm-up or load-delivery criteria are excluded from the medians.
