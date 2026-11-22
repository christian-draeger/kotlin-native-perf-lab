# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

The HTTP/1.1 load generator shares the server host. See the environment record for limits.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| ktor-jvm | `/health` | 5 / 5 | 17,985 [17,186–18,078] | 697.0 [687.0–717.0] | 3,335.0 [2,652.0–3,451.0] | 7,483.0 [6,260.0–8,503.0] | 192.2 [189.0–201.5] | 287.5 [279.5–289.4] |
| ktor-jvm | `/json` | 5 / 5 | 18,078 [17,556–19,741] | 686.0 [629.0–710.0] | 2,746.0 [2,583.0–3,168.0] | 6,556.0 [5,714.0–7,406.0] | 191.2 [170.8–194.5] | 282.4 [277.1–288.1] |
| ktor-jvm | `/users/123` | 5 / 5 | 17,352 [17,121–18,573] | 719.0 [668.0–728.0] | 3,154.0 [2,596.0–3,254.0] | 7,058.0 [6,457.0–8,150.0] | 198.8 [184.1–202.0] | 291.3 [278.9–298.0] |
| ktor-native | `/health` | 5 / 5 | 9,711 [9,543–10,041] | 1,495.0 [1,471.0–1,538.0] | 3,263.0 [3,156.0–3,439.0] | 5,262.0 [4,927.0–5,396.0] | 274.2 [267.9–278.8] | 157.7 [143.6–167.3] |
| ktor-native | `/json` | 5 / 5 | 9,167 [8,788–9,735] | 1,616.0 [1,512.0–1,682.0] | 3,352.0 [3,135.0–3,612.0] | 5,332.0 [5,084.0–5,667.0] | 293.0 [275.1–303.7] | 158.7 [147.9–180.0] |
| ktor-native | `/users/123` | 5 / 5 | 9,245 [8,940–9,304] | 1,593.0 [1,578.0–1,665.0] | 3,363.0 [3,352.0–3,587.0] | 5,404.0 [5,215.0–5,934.0] | 290.2 [288.3–298.0] | 158.4 [150.3–177.0] |
| rust-axum | `/health` | 5 / 5 | 132,484 [128,333–133,497] | 124.0 [110.0–126.0] | 220.0 [215.0–235.0] | 1,017.0 [1,003.0–1,057.0] | 10.5 [10.4–11.0] | 3.3 [3.2–3.3] |
| rust-axum | `/json` | 5 / 5 | 129,364 [128,361–132,904] | 126.0 [118.0–129.0] | 222.0 [212.0–232.0] | 958.0 [781.0–976.0] | 10.9 [10.5–11.0] | 3.3 [3.2–3.4] |
| rust-axum | `/users/123` | 5 / 5 | 127,482 [126,034–131,687] | 128.0 [107.0–132.0] | 223.0 [218.0–229.0] | 845.0 [812.0–953.0] | 11.2 [11.0–11.5] | 3.3 [3.3–3.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors or wrk HTTP errors in warm-up or measurement are excluded from the medians.
