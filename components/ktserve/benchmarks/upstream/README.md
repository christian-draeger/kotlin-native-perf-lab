# HTTP upstream pilot

The four release fixtures call the same separate loopback HTTP origin. Each frontend receives persistent HTTP/1.1 traffic and opens one upstream connection per request. ktServe and the two Ktor fixtures use Ktor CIO as the outbound client. Axum uses Hyper. This compares the complete frontend request path, including each outbound client and the shared origin's actual waits. The loopback origin limits the scope to a local dependency.

| Scenario | Share | Origin behavior | Expected frontend result |
| --- | ---: | --- | --- |
| Fast | 70% | 5 ms wait | HTTP 200 with `origin-ready` |
| Slow | 20% | 40 ms wait | HTTP 200 with `origin-ready` |
| Deadline | 5% | 450 ms wait | HTTP 504 at a 150 ms deadline |
| Disconnect | 5% | Close the upstream socket before its response | HTTP 502 with `origin-unavailable` |

The full pilot interleaves three trials at 200 and 800 offered requests per second per frontend. Pull requests run the 800/s subset. Each trial has 15 seconds of preconditioning, ten seconds of warm-up and eight seconds of measurement at the offered rate. Preconditioning records startup behavior separately. k6 preallocates enough virtual users for 200 ms of in-flight work at each scenario rate and a minimum of 16 per scenario. The parity probe checks status, body, media type, HTTP/1.1 and exactly one origin request per known scenario. It records whether a timed-out call closed the upstream connection before the delayed response became ready. This observation stays separate from the measurement's status and timing metrics.

The report records p50, p95 and p99 separately for each scenario. HTTP 502 and 504 are expected only for their matching scenarios. Both scored windows, warm-up and measurement, require the expected bodies and statuses, zero skipped arrivals and delivered counts within 1% or one arrival of the schedule per scenario. It also checks that the pinned k6 process averaged below 90% of its one logical CPU. This is an exploratory GitHub-hosted pilot with two logical CPUs for the frontend, one for k6 and one for the origin on the same VM. The artifact contains raw k6 summaries, process CPU seconds and peak RSS, binary hashes, toolchains, CPU topology and origin counters.

From the repository root on Linux after installing Gradle, the pinned Rust toolchain, Python 3.12+ and k6:

```bash
bash scripts/ci-gradle.sh -p competitors/ktor jvmDist linkReleaseExecutableNative
bash scripts/ci-gradle.sh -p components/ktserve linkUpstreamProbeReleaseExecutableNative
cargo build --release --locked --features upstream --manifest-path competitors/rust-axum/Cargo.toml
python3 scripts/benchmark-upstream.py /tmp/ktserve-upstream --smoke-only
python3 scripts/benchmark-upstream.py /tmp/ktserve-upstream
```
