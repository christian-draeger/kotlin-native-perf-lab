# Standalone extension overhead

This benchmark runs the Taskboard API's stateless fixture in a fresh process for
each case. Every case accepts the same valid typed `POST /api/tasks/preview` and
returns HTTP 200 with the same JSON bytes. The workload uses HTTP/1.1 keep-alive,
a fixed connection budget, a Bearer header and a browser Origin. CORS adds its
own response headers. The timed workload only exercises actual requests, while
the Taskboard smoke checks allowed and denied preflights.

| Mode | Work measured |
| --- | --- |
| `none` | Typed JSON decode and encode with zero installed extensions |
| `noop` | One request hook returning `Continue` |
| `bearer` | Parse the Authorization header and make the credential available to the app |
| `cors` | Match the Origin and attach the CORS response headers |
| `validation` | Run the rules for the decoded request body |
| `openapi` | Generate the API contract at startup and measure the warm request path afterward |
| `access-log` | Record and write each completed exchange to a filesystem-backed stderr file |
| `all` | Run the five portable modules above together |
| `otel-unsampled` | JVM tracing with 0% root sampling |
| `otel-sampled` | JVM tracing with 10% root sampling and bounded batch processing |

The tracing trials use an in-process discard exporter with a 2,048-entry queue.
They measure instrumentation, span creation and local batching. Collector traffic
and collector memory require a separate trial. The comparison uses one build per
runtime. It measures runtime activation with the same distribution on both sides.

## Run on separate Linux machines

Use JDK 21 for the JVM build. Build the Native release on a supported Kotlin/Native
compiler host. When building for Linux ARM64 from x86-64, select
`-Pbenchmark.targetArch=aarch64` and copy the release executable to the server.
Include the Kotlin, Gradle and JDK versions, target and exact build commands in the
toolchain file. Use a Linux server and an independent Linux load host with Vegeta,
GNU time, `taskset` and Python 3.11+. Choose CPU masks and a server address reachable
from the load host. Place output on a disk-backed filesystem for the logging trials.

```sh
app=components/ktserve/examples/extensions
./components/ktserve/gradlew -p "$app" installJvmConsumer linkReleaseExecutableNative

export LOAD_SSH=bench-load
python3 scripts/benchmark-extensions.py native \
  "$app/build/bin/native/releaseExecutable/extension-consumer.kexe" results/extensions-native \
  --toolchain build-toolchain.txt --bind 192.0.2.10 --server-cpus 2,3 --load-cpus 2,3
python3 scripts/benchmark-extensions.py jvm "$app/build/jvm-consumer" results/extensions-jvm \
  --toolchain build-toolchain.txt --bind 192.0.2.10 --server-cpus 2,3 --load-cpus 2,3
```

Select rates from a capacity sweep and pass the same `--rates` to paired cases.
`--modes none validation` selects a subset. The default repeats five times and
rotates case order between repeats. For a wiring check, unset `LOAD_SSH` and pass
`--local` with disjoint CPU masks and low fixed rates. Those runs are recorded as
same-host diagnostics.

## Read the output

- `environment.json` records Git, build artifact hashes, toolchain, both hosts,
  CPU affinity, the load generator and the exact workload.
- `runs.csv` records successful requests/sec, p50/p95/p99, process CPU time, CPU per
  million successful requests, startup-to-first-response time, initial RSS and peak
  RSS. The peak covers startup, warm-up and timed load. CPU for access-log modes
  includes the wait for the file writer to settle.
- `summary.json` gives median, min and max and median paired deltas from `none`.
  Only runs with valid offered load, successful responses and load-generator CPU
  headroom contribute. Raw Vegeta data, reports and server logs remain alongside it.

The [original logger benchmark](benchmark-access-log.md) covers log delivery and
the previous logger implementation. The pre-extension fast-path comparison and
controlled results are tracked in [#225](https://github.com/christian-draeger/ktserve/issues/225).
