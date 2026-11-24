# ktServe HTTP/2 session experiment

This experiment exercises the runtime boundary to nghttp2 on Linux. nghttp2 implements HTTP/2 framing, HPACK, stream state and flow control under the MIT license. Kotlin owns the application-visible stream state. The C adapter exposes the library's memory interface. The h2c executable attaches it to the shared epoll transport and ktServe parity routes on loopback. [nghttp2 programmer's guide](https://nghttp2.org/documentation/programmers-guide.html) · [License](https://github.com/nghttp2/nghttp2/blob/master/COPYING)

## Run the probe

On Ubuntu 24.04 x86_64, install the distribution's `libnghttp2-dev` package and run from the repository root with JDK 21.

```sh
./components/ktserve/gradlew -p components/ktserve/experiments/http2-nghttp2 \
  -Pnghttp2.headers=/usr/include \
  -Pnghttp2.archive=/usr/lib/x86_64-linux-gnu/libnghttp2.a runReleaseExecutableNative
```

The workflow uses the Ubuntu SDK for each architecture, records the package versions, builds both binaries with Kotlin/Native and checks the corresponding executable on each Linux runner.

Use `linkH2cReleaseExecutableNative` with the same SDK parameters to build the socket executable. Run it on loopback and use a curl build with HTTP/2 support.

```sh
components/ktserve/experiments/http2-nghttp2/build/bin/native/h2cReleaseExecutable/ktserve-h2c.kexe 8080
curl --http2-prior-knowledge -fsS http://127.0.0.1:8080/users/123
```

The experimental `serveH2c` entry point uses the same routing block, limits and shutdown callback as `serve`. It supports buffered text and JSON handlers, POST body sinks and response sources. The `/upload` and `/stream` fixtures exercise these APIs over HTTP/2. Each connection uses one read range and one write range and admits up to eight streams. Body sinks have byte and time limits, and the adapter returns receive credit after consuming each slice. SIGTERM stops accepting and queues GOAWAY before draining existing streams to the configured deadline. The executable is a loopback test for prior knowledge.

## Compare ktServe and Axum over HTTP/2

Run **Actions → HTTP/2 session probe → Run workflow** with `run_pilot` enabled for exploratory Linux x86_64 and ARM64 measurements. The pilot builds Axum with its optional HTTP/2 feature in a separate target directory. It uses the existing typed `/json` handler in both release executables and verifies the exact response and HTTP version before every trial. [Workflow](../../../../.github/workflows/http2-nghttp2-probe.yml)

The same ktServe executable runs twice per cell. The default keeps the kernel's socket policy. The second launch adds `--tcp-nodelay` after the port, which sets `TCP_NODELAY` on each accepted socket before exposing it to the HTTP/2 session. The epoll transport owns the descriptor and closes it on configuration failure. The regular HTTP/1.1 launchers retain their socket policy. A separate traced HTTP/2 request verifies the option syscall on an accepted socket in the enabled mode. Timed measurements run without tracing.

Each engine runs on the same two-logical-CPU allowance. Two separate logical CPUs run `h2load` over h2c on loopback. The pilot holds 16 connections constant and tests up to one and eight streams per connection. It warms each server for five seconds and then measures a fresh client run for three seconds. Three repetitions rotate the launch order. Both processes use the same VM, and raw request logging consumes load generator resources. Use the recorded load CPU time to interpret the throughput. [h2load options](https://nghttp2.org/documentation/h2load.1.html)

Each architecture produces a workflow artifact with trial throughput, p50/p95/p99, error counts, server CPU and peak RSS, load generator usage, build metadata, the raw console logs and compressed request timings. The broader matched streaming, TLS and independent load-host comparison remains part of [#81](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/81).

### Compare behind the same TLS ingress

Select both **Run the exploratory ktServe and Axum HTTP/2 comparison** and **Put each origin behind the same Caddy TLS configuration** in the workflow. Every trial starts its own Caddy process on loopback with the same configuration and temporary certificate. The certificate is checked in the preflight, and h2load must report HTTP/2 ALPN and a TLS version. Caddy uses h2c to each origin. The connection and stream counts describe the client side of the ingress. Caddy and the origin share the two server CPUs. The artifact records each process’s CPU time and memory separately, plus raw TLS and protocol logs. The TLS pilot is exploratory and uses the same JSON endpoint. [#81](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/81)

## Serve HTTPS at the ingress

The [Caddy configuration](ingress/Caddyfile) passes HTTPS requests to the experimental native server over loopback HTTP/2. It accepts HTTP/2 and HTTP/1.1 at the TLS listener. Start `ktserve-h2c` on port 8080, set `KTSERVE_TLS_CERT` and `KTSERVE_TLS_KEY` to the paths of a matching certificate and private key, and start Caddy with `caddy run --config ingress/Caddyfile --adapter caddyfile` from this directory. Configure the certificate hostname with `KTSERVE_TLS_HOST` and the listener with `KTSERVE_TLS_BIND` and `KTSERVE_TLS_PORT`. Set `KTSERVE_H2C_PORT` when the native server uses a different port. [Caddy reverse proxy transport](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy) · [TLS configuration](https://caddyserver.com/docs/caddyfile/directives/tls)

The smoke test creates a short-lived localhost certificate, starts both processes and verifies TLS with the generated certificate. With Ubuntu's `python3-h2` package installed, run `bash ingress/smoke.sh build/bin/native/h2cReleaseExecutable/ktserve-h2c.kexe`. The independent HTTP/2 test client verifies multiplexing, cancels a stream whose receive window is blocked and checks that the following request still succeeds. The direct h2c test also observes GOAWAY during a live transfer. The h2c server's stream and connection limits apply behind Caddy. Stop the ingress first so it drains its downstream connections before the native process shuts down.

## Boundary and ownership

| Operation | Ownership and progress |
| --- | --- |
| Receive bytes | Borrow one pinned Kotlin input range until nghttp2 returns. Incomplete frames can span calls. Copy header values and data slices needed by the Kotlin caller before the callback returns. |
| Deliver request events | Surface headers, data, remote end and stream close with stream IDs. Keep handler and routing state in Kotlin. Enforce the configured application header and body limits before accepting a production stream. |
| Apply backpressure | Return flow-control credit after the application consumes data. The consumer can delay credit while retaining its bounded slice. Multiplexed streams also share the connection window. |
| Send bytes | Pull bounded output into caller-owned storage. The C adapter retains the range returned by nghttp2 across short pulls and only advances the library once it can release that range. A transport can lease the caller buffer until its write completes. |
| Finish or cancel | Handle remote EOF and stream close separately. Release an application's response source on normal stream closure or reset. Dispose the native session before dropping its Kotlin callback reference. |

The probe checks two streams sharing a session, fragmentation on each memory input, bounded output pulls, response data and an explicit `RST_STREAM` cancellation. A 96 KiB response stalls until the receiver returns credit, while a separate small stream completes. The test driver counts wire bytes and input fragments. These counts describe the exercised boundary. Throughput and latency require the matched workload in [#81](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/81).

For [#79](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/79), exercise resource limits and multiplexed traffic over the real transport. The Caddy example exercises TLS and ALPN at the protocol-selection boundary for [#80](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/80). For JVM integrations, existing Ktor/Netty and Spring WebFlux/Reactor Netty engines can own HTTP/2 framing, leaving application handlers on the portable side of the adapter. [Ktor HTTP/2](https://ktor.io/docs/server-http2.html) · [Spring WebFlux server support](https://docs.spring.io/spring-boot/reference/web/reactive.html)
