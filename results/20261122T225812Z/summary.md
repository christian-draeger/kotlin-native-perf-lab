# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

The HTTP/1.1 load generator shares the server host. See the environment record for limits.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| ktor-jvm | `/health` | 5 / 5 | 16,341 [15,857–18,239] | 686.0 [636.0–727.0] | 3,144.0 [2,878.0–3,562.0] | 7,219.0 [6,849.0–8,093.0] | 216.9 [189.2–222.8] | 289.1 [285.2–294.0] |
| ktor-jvm | `/json` | 5 / 5 | 15,998 [15,615–16,842] | 715.0 [679.0–735.0] | 3,392.0 [3,288.0–3,741.0] | 8,322.0 [7,338.0–8,906.0] | 221.1 [207.1–227.3] | 286.3 [283.3–295.2] |
| ktor-jvm | `/users/123` | 5 / 5 | 15,435 [12,570–16,749] | 741.0 [690.0–969.0] | 3,433.0 [3,213.0–4,315.0] | 7,601.0 [6,974.0–8,601.0] | 229.6 [209.9–291.2] | 290.1 [283.2–297.9] |
| ktor-native | `/health` | 5 / 5 | 9,342 [9,274–9,416] | 1,591.0 [1,583.0–1,601.0] | 3,022.0 [2,930.0–3,227.0] | 4,761.0 [4,473.0–5,044.0] | 304.7 [302.4–305.7] | 150.3 [143.7–171.0] |
| ktor-native | `/json` | 5 / 5 | 9,072 [9,019–9,216] | 1,658.0 [1,635.0–1,662.0] | 3,101.0 [2,993.0–3,178.0] | 4,740.0 [4,419.0–4,858.0] | 314.0 [310.4–315.9] | 150.9 [142.2–157.8] |
| ktor-native | `/users/123` | 5 / 5 | 8,796 [8,717–8,846] | 1,695.0 [1,681.0–1,718.0] | 3,230.0 [3,201.0–3,289.0] | 5,152.0 [4,822.0–5,286.0] | 325.0 [322.1–325.5] | 191.0 [143.6–194.0] |
| rust-axum | `/health` | 5 / 5 | 102,666 [101,324–103,540] | 146.0 [144.0–148.0] | 260.0 [258.0–278.0] | 741.0 [668.0–843.0] | 15.5 [15.3–15.8] | 4.1 [4.0–4.1] |
| rust-axum | `/json` | 5 / 5 | 101,100 [99,972–103,560] | 145.0 [141.0–146.0] | 271.0 [262.0–278.0] | 725.0 [709.0–840.0] | 15.9 [15.4–16.2] | 4.1 [3.9–4.2] |
| rust-axum | `/users/123` | 5 / 5 | 97,922 [97,718–98,925] | 146.0 [140.0–149.0] | 297.0 [288.0–301.0] | 865.0 [795.0–891.0] | 16.8 [16.5–16.9] | 4.0 [4.0–4.1] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors or wrk HTTP errors in warm-up or measurement are excluded from the medians.
