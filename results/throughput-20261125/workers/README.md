# Linux epoll worker qualification

The two measurements use the ktServe 0.3.0 serving code. The mixed-load comparison runs the same release binary in both modes. The closed-loop CPU-budget study ran at main `796c638`. Each Linux job pins the server to two logical CPUs and the generator to a disjoint pair on the same VM. The results are exploratory within each runner.

## Persistent small JSON at saturation

The three trials per configuration used 64 HTTP/1.1 connections. All 30 trials across both architectures were valid.

| Linux | ktServe 1 loop | ktServe 2 loops | Micronaut 2 loops | Axum 2 workers | ktServe p99 at achieved load |
| --- | ---: | ---: | ---: | ---: | ---: |
| x86_64 | 158,573 req/s | 273,898 req/s | 220,541 req/s | 263,369 req/s | 0.675 → 1.889 ms |
| ARM64 | 95,698 req/s | 183,622 req/s | 102,241 req/s | 193,918 req/s | 0.861 → 1.529 ms |

The second loop raised median ktServe throughput by 72.7% and 91.9%, respectively. Saturation latency describes each configuration's own achieved rate. The raw report includes ranges, process CPU, peak RSS and verified thread configuration. [Raw results](cpu-budget/) · [Workflow](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45585545966)

## Mixed traffic at matched offered load

All 48 trials qualified delivery and completed without failed responses or unsent arrivals. The second loop increased paired median CPU per million successful replies in each of the eight cells. Each three-trial cell also used more peak resident memory. Fresh TCP includes a verified connection and handshake timing for every request.

| Linux | Connection mode | Offered req/s | Paired median CPU cost change | Per-operation SLO, one → two loops |
| --- | --- | ---: | ---: | ---: |
| x86_64 | Persistent | 800 | +9.82% | 3/3 → 3/3 |
| x86_64 | Persistent | 2,400 | +7.31% | 3/3 → 3/3 |
| x86_64 | Fresh TCP | 800 | +8.04% | 3/3 → 3/3 |
| x86_64 | Fresh TCP | 1,600 | +14.78% | 3/3 → 3/3 |
| ARM64 | Persistent | 800 | +12.37% | 3/3 → 3/3 |
| ARM64 | Persistent | 2,400 | +10.57% | 3/3 → 2/3 |
| ARM64 | Fresh TCP | 800 | +16.65% | 3/3 → 3/3 |
| ARM64 | Fresh TCP | 1,600 | +14.65% | 3/3 → 3/3 |

At 2,400 on ARM64, one two-loop trial exceeded the 20 ms budget for JSON, catalog and two upload sizes. The archive preserves that trial and the separate development-host smoke. Keep the one-loop default for the supported production profile. An explicit two-loop setting serves Linux deployments that benefit from higher throughput on CPU-limited, short handlers. Its CPU, memory and tail-latency costs should be evaluated under the user's actual load. [Raw results](hosted/) · [Workflow](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45584060389)
