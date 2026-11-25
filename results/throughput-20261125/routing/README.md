# Route dispatch evaluation

The standalone fixture registers one exact and one path-parameter route per resource. All HTTP variants construct and serialize the same response. The isolated matcher additionally includes misses and validates the dispatch checksum. Run the **routing** suite of [Linux diagnostic profiles](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/workflows/profile.yml) for the Native and JVM comparisons. The server and generator use disjoint logical CPU masks in the same VM. Values are exploratory and describe this fixture.

## Iterations

- Deferring parameter-map allocations improved large linear scans. On the local JVM, the initial index candidate also changed the small matcher and reduced two-route saturation throughput from roughly 344,000 to 304,000 req/s. The same hosted JVM cell showed overlapping throughput. At 5,000 offered requests/s the local generator dropped arrivals in every trial. The hosted audit identifies qualified and unqualified cells separately. [Initial hosted data](hosted-initial/)
- The next version restored the original small matching loop and skipped unproductive indexes. It met the 2,000/s delivery target in all 24 local Native and JVM trials. The local small JVM saturation reduction persisted, so that version remained a prototype. [Native trial data](final-native-saturation/) · [JVM trial data](final-jvm-saturation/)
- The final candidate matches path ranges before allocating parameters and compiles an index when segment count or a literal position narrows large tables. Exact-route priority, declaration order, empty literal segments, named parameters and method handling have test coverage.

## Generated dispatch prototype

The prototype generated a Kotlin selector for the 512-resource fixture with direct typed parameter binding from the path. It retains the production HTTP server and the same kotlinx.serialization response. The generation script applies only to the recorded diagnostic commit.

| ARM64 Linux VM | Indexed reference → generated req/s | Median paired throughput change | Median paired CPU cost change |
| --- | ---: | ---: | ---: |
| Native | 227,633 → 195,572 | −16.18% | +17.55% |
| JVM | 294,369 → 292,830 | −3.62% | +4.36% |

The Native variant had lower throughput and higher CPU cost in all five pairs. The JVM result varied widely by pair. Its p99 was lower in this saturation mode, where each configuration reached a different request rate. These results do not support adding a consumer code generator for throughput. Keep the prototype and the trial data so a new hypothesis can be tested independently. [Generator](generated-source/) · [Native trials](codegen-native-saturation/) · [JVM trials](codegen-jvm-saturation/)

## Final candidate on the ARM64 development VM

The exact source at `95a624dee9cb74dd7a258b740cccca926d4457fc` finished all 24 fixed-rate and all 24 saturation trials without response errors or unsent arrivals. The JVM saturation control used a 12-second warm-up. The table gives trial medians from each group, followed by CPU at 2,000 offered requests/s.

| Runtime | Registered routes | Saturation req/s, baseline → final | CPU s per million at 2,000/s |
| --- | ---: | ---: | ---: |
| Native | 2 | 172,151 → 185,332 | 43.982 → 43.996 |
| Native | 1,024 | 26,086 → 169,872 | 148.059 → 47.966 |
| JVM | 2 | 279,061 → 308,618 | 61.024 → 62.962 |
| JVM | 1,024 | 115,194 → 257,847 | 84.975 → 70.000 |

Two-route JVM throughput improved in two individual pairs and fell 0.99% in the third. Fixed-load measurements can still vary. For example, local Native 1,024-route median p99 rose from 4.597 to 6.981 ms even as server CPU declined. Distinguish the local VM from the hosted runners and use the individual pairs rather than the medians to see variation. [Local results](local-audit.txt)

A separately instrumented 1,024-route JVM HTTP run confirms the allocation mechanism. LinkedHashMap was the largest allocation class in the baseline's weighted JFR samples with ktServe frames. It fell outside the ten largest classes with the index and range matcher. The updated profile exposes the direct path matcher. JFR weights cover the recorded process interval and are not allocated bytes per request. This profile has no weight in the throughput comparison. [JFR aggregates](jfr-routing-aggregate.json)

## Final GitHub Actions matrix

All 108 HTTP trials across Native x86_64, Native ARM64 and JVM x86_64 passed exact-source, delivery and response checks. The fixed-rate test used 2,000 offered requests/s with zero load-generator drops. The saturation figures are medians of three trials per variant.

| Linux runtime | 1,024 routes at saturation, req/s | 1,024 routes at 2,000/s, CPU s per million | Two-route saturation, req/s |
| --- | ---: | ---: | ---: |
| Native x86_64 | 27,782 → 123,655 | 54.995 → 19.186 | 133,962 → 138,461 |
| Native ARM64 | 10,732 → 78,321 | 120.813 → 25.831 | 82,239 → 87,754 |
| JVM x86_64 | 92,528 → 149,662 | 39.160 → 33.328 | 155,073 → 156,348 |

At 2,000/s with 1,024 routes, median p99 was 0.749 → 0.665 ms on Native x86_64, 1.153 → 1.232 ms on Native ARM64 and 0.762 → 0.635 ms on JVM x86_64. The throughput and CPU gains justify selecting the portable index and range matcher. The two-route controls show smaller changes and fixed-rate p99 varies by runtime and host. The measured gain applies to the registered route mix. [Final files](hosted-final/) · [CI run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45589829795)
