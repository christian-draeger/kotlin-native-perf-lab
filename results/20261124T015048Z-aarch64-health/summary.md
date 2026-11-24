# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

Load generator: local HTTP/1.1 loopback; same host and CPU pool as servers. See the environment record.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| kotlin-reactor | `/health` | 3 / 3 | 114,426 [110,810–114,662] | 126.0 [124.0–131.0] | 249.0 [249.0–257.0] | 285.0 [276.0–291.0] | 8.9 [8.8–9.1] | 15.9 [15.9–15.9] |
| ktor-jvm | `/health` | 3 / 3 | 19,993 [18,971–20,344] | 613.0 [603.0–664.0] | 2,646.0 [2,553.0–2,649.0] | 6,936.0 [6,217.0–7,408.0] | 169.4 [166.6–181.1] | 284.2 [276.1–296.6] |
| ktor-native | `/health` | 3 / 3 | 9,948 [9,711–9,948] | 1,479.0 [1,468.0–1,516.0] | 3,292.0 [3,240.0–3,315.0] | 5,224.0 [5,198.0–5,543.0] | 272.5 [272.2–278.0] | 165.6 [152.8–197.2] |
| rust-axum | `/health` | 3 / 3 | 138,676 [136,829–139,139] | 90.0 [89.0–93.0] | 244.0 [241.0–245.0] | 1,126.0 [1,035.0–1,181.0] | 11.2 [10.9–11.5] | 3.3 [3.2–3.4] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors, wrk HTTP errors, or unmet warm-up stability are excluded from the medians.
