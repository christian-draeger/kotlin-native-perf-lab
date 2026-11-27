# Native OpenTelemetry probe

This standalone HTTP/1.x experiment uses the public ktServe extension hooks with
OpenTelemetry Kotlin SDK and OTLP exporter `0.11.0`. It extracts W3C trace context
from request headers, records the matched route template, and ends the span when
ktServe reports exchange completion. The trace exports outside the request loop.
The listener serves one request so the smoke test can check SDK flush and shutdown.

## Run against a Collector

From `components/ktserve`, start an OpenTelemetry Collector with the provided
configuration. The host port is bound to loopback.

```sh
probe=experiments/opentelemetry-native
docker run --rm --name ktserve-otel-collector -p 127.0.0.1:4318:4318 \
  -v "$PWD/$probe/collector.yaml:/etc/otelcol-contrib/config.yaml:ro" \
  otel/opentelemetry-collector-contrib:0.165.0
```

From a second terminal on Linux, build the release executable and send the test
request. `-Pbenchmark.targetArch=aarch64` selects the ARM64 build when cross-compiling
on Linux x86-64.

```sh
probe=experiments/opentelemetry-native
./gradlew -p "$probe" linkReleaseExecutableNative
binary="$probe/build/bin/native/releaseExecutable/ktserve-otel-native-probe.kexe"
python3 "$probe/smoke.py" "$binary" http://127.0.0.1:4318
docker logs ktserve-otel-collector 2>&1 | grep -E 'Trace ID|Parent ID|Name +:|http\.route|http\.response\.status_code'
python3 "$probe/smoke.py" "$binary" http://127.0.0.1:1 --expect-export-failure
```

The Collector should log trace ID `4bf92f3577b34da6a3ce929d0e0e4736`, parent ID
`00f067aa0ba902b7`, span name `GET /widgets/{id}`, `http.route=/widgets/{id}` and
status `200`. The failure check expects the HTTP response to succeed and SDK flush
and shutdown to report the failed export.

## Preview scope

The official Maven SDK and OTLP exporter have Linux x86-64 and ARM64 variants.
Both macOS ARM64 variants are absent in `0.11.0`. Upstream lists Linux as Tier 2
and runs its own Linux CI tests on x86-64. The exporter uses the Ktor CIO client
on Linux. These dependencies belong in an optional module if ktServe ships
Native support. [Target matrix](https://github.com/open-telemetry/opentelemetry-kotlin/tree/v0.11.0#supported-targets) · [OTLP build](https://github.com/open-telemetry/opentelemetry-kotlin/blob/v0.11.0/exporters-otlp/build.gradle.kts)

The experiment bounds the SDK batch processor at 32 queued spans and batches of 16.
It uses a one-second HTTP request timeout. The SDK's bounded queue uses non-blocking
insertion; a full queue can drop spans. The HTTP timeout covers individual requests,
and the processor has its own export timeout. A production extension needs an
explicit shutdown budget and overload tests, with the sampling and exporter settings
reported alongside end-to-end overhead. [Batch processor](https://github.com/open-telemetry/opentelemetry-kotlin/blob/v0.11.0/exporters-core/src/commonMain/kotlin/io/opentelemetry/kotlin/export/BatchTelemetryProcessor.kt)
