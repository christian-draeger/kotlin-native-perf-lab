# Preliminary performance comparison

Each cell shows the median [min–max] across valid runs. Latency columns summarize
the corresponding per-run percentiles. The p99 median is a summary of run-to-run
behavior and does not estimate a pooled request-level p99.

The HTTP/1.1 load generator shares the server host. See the environment record for limits.

| Variant | Endpoint | Valid / total | Req/s | p50 (µs) | p95 (µs) | p99 (µs) | CPU s / 1M req | Peak RSS (MiB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| ktor-jvm | `/health` | 5 / 5 | 47,521 [43,050–47,788] | 246.0 [243.0–270.0] | 1,519.0 [1,347.0–1,713.0] | 4,962.0 [4,380.0–6,461.0] | 66.2 [65.6–73.9] | 362.6 [349.2–388.4] |
| ktor-jvm | `/json` | 5 / 5 | 44,716 [40,803–49,562] | 260.0 [235.0–285.0] | 1,683.0 [1,459.0–1,925.0] | 5,669.0 [4,915.0–6,967.0] | 71.1 [63.5–79.5] | 362.2 [350.4–371.0] |
| ktor-jvm | `/users/123` | 5 / 5 | 41,102 [34,592–43,132] | 284.0 [266.0–321.0] | 2,122.0 [1,998.0–2,372.0] | 7,163.0 [5,501.0–7,174.0] | 78.6 [75.5–95.5] | 374.8 [353.7–430.3] |
| ktor-native | `/health` | 5 / 5 | 16,062 [15,859–16,343] | 889.0 [877.0–893.0] | 2,702.0 [2,600.0–2,765.0] | 4,131.0 [3,926.0–4,384.0] | 161.4 [159.3–164.1] | 149.7 [138.7–183.4] |
| ktor-native | `/json` | 5 / 5 | 15,465 [14,840–16,190] | 923.0 [881.0–948.0] | 2,714.0 [2,555.0–3,034.0] | 4,119.0 [3,937.0–4,551.0] | 168.5 [161.0–175.2] | 150.3 [145.4–154.6] |
| ktor-native | `/users/123` | 5 / 5 | 14,704 [14,444–16,029] | 960.0 [899.0–987.0] | 3,059.0 [2,411.0–3,325.0] | 4,667.0 [3,749.0–5,079.0] | 176.9 [164.3–181.8] | 157.0 [146.3–167.3] |
| rust-axum | `/health` | 5 / 5 | 175,366 [166,946–176,944] | 77.0 [75.0–79.0] | 165.0 [152.0–165.0] | 398.0 [354.0–420.0] | 8.8 [8.5–8.9] | 4.2 [4.2–4.3] |
| rust-axum | `/json` | 5 / 5 | 174,602 [169,657–177,142] | 78.0 [78.0–80.0] | 164.0 [155.0–168.0] | 389.0 [333.0–485.0] | 8.7 [8.5–9.2] | 4.2 [4.0–4.3] |
| rust-axum | `/users/123` | 5 / 5 | 170,300 [163,395–174,209] | 81.0 [80.0–84.0] | 163.0 [155.0–178.0] | 390.0 [321.0–410.0] | 9.0 [8.8–9.1] | 4.2 [4.1–4.3] |

The raw logs and CSV include every recorded trial. Trials with zero responses,
socket errors or wrk HTTP errors in warm-up or measurement are excluded from the medians.
