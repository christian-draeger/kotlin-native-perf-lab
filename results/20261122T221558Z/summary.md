# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

The HTTP/1.1 load generator shares the server host. See the environment record for limits.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| ktor-jvm | `/health` | 5 / 5 | 45,397 [44,661–48,711] | 225.0 [221.0–228.0] | 114,071.0 [94,413.0–136,785.0] | 189,500.0 [160,236.0–235,025.0] | 52.0 [51.4–61.3] | 926.3 [918.1–969.2] |
| ktor-jvm | `/json` | 5 / 5 | 42,905 [34,234–44,590] | 236.0 [229.0–268.0] | 107,787.0 [99,761.0–151,350.0] | 184,299.0 [152,913.0–264,047.0] | 57.1 [53.4–72.2] | 966.1 [939.3–986.7] |
| ktor-jvm | `/users/123` | 5 / 5 | 38,927 [34,691–40,164] | 250.0 [247.0–274.0] | 123,656.0 [109,475.0–129,899.0] | 190,308.0 [174,297.0–206,086.0] | 60.4 [57.8–69.7] | 965.7 [920.3–987.7] |
| ktor-native | `/health` | 5 / 5 | 14,896 [14,137–15,063] | 997.0 [978.0–1,039.0] | 2,158.0 [2,030.0–2,609.0] | 3,673.0 [3,502.0–16,590.0] | 182.2 [178.2–190.4] | 184.5 [176.7–190.0] |
| ktor-native | `/json` | 5 / 5 | 14,413 [14,373–14,917] | 1,018.0 [1,000.0–1,029.0] | 2,519.0 [2,061.0–2,609.0] | 4,184.0 [3,460.0–4,289.0] | 184.6 [181.5–191.5] | 182.5 [173.2–216.7] |
| ktor-native | `/users/123` | 5 / 5 | 14,254 [14,203–14,665] | 1,024.0 [1,004.0–1,034.0] | 2,455.0 [2,219.0–2,748.0] | 4,029.0 [3,895.0–4,548.0] | 186.7 [183.7–194.7] | 190.6 [177.4–193.5] |
| rust-axum | `/health` | 5 / 5 | 252,769 [242,570–261,105] | 60.0 [58.0–64.0] | 114.0 [97.0–205.0] | 1,212.0 [923.0–1,383.0] | 5.1 [5.0–5.5] | 10.2 [10.0–10.7] |
| rust-axum | `/json` | 5 / 5 | 248,752 [238,760–252,992] | 61.0 [59.0–64.0] | 188.0 [98.0–281.0] | 1,319.0 [977.0–1,529.0] | 5.2 [5.1–5.5] | 10.3 [10.1–10.5] |
| rust-axum | `/users/123` | 5 / 5 | 239,150 [227,076–241,087] | 64.0 [63.0–68.0] | 121.0 [106.0–351.0] | 1,431.0 [916.0–1,715.0] | 5.5 [5.5–5.7] | 10.4 [9.9–10.6] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors or wrk HTTP errors in warm-up or measurement are excluded from the medians.
