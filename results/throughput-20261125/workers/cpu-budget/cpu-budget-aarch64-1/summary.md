# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1; separate server and load CPU affinity sets on one host. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| axum-2-tokio-workers | `/json` | 3 / 3 | 193,918 [192,298–193,986] | 321.0 [321.0–326.0] | 469.0 [468.0–478.0] | 526.0 [525.0–618.0] | 9.9 [9.9–10.0] | 4.4 [4.4–4.6] |
| ktserve-1-worker | `/json` | 3 / 3 | 95,698 [94,404–99,117] | 658.0 [637.0–659.0] | 740.0 [681.0–759.0] | 861.0 [859.0–890.0] | 10.7 [10.3–10.8] | 17.0 [16.8–17.1] |
| ktserve-2-workers | `/json` | 3 / 3 | 183,622 [181,132–189,150] | 367.0 [325.0–398.0] | 852.0 [843.0–866.0] | 1,529.0 [1,433.0–1,650.0] | 10.6 [10.3–10.7] | 19.6 [19.6–19.9] |
| micronaut-1-io-thread | `/json` | 3 / 3 | 45,599 [42,501–45,611] | 1,333.0 [1,332.0–1,426.0] | 2,186.0 [2,185.0–2,381.0] | 3,409.0 [2,957.0–3,419.0] | 25.2 [24.8–26.9] | 233.9 [229.5–236.3] |
| micronaut-2-io-threads | `/json` | 3 / 3 | 102,241 [92,765–103,078] | 561.0 [559.0–594.0] | 2,404.0 [2,099.0–2,417.0] | 4,222.0 [4,117.0–4,299.0] | 19.5 [19.4–21.5] | 261.8 [228.4–294.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
load errors, or unmet warm-up or load-delivery criteria are excluded from the medians.
