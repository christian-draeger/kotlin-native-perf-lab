# NativeServe HTTP/2 session experiment

This experiment exercises the runtime boundary to nghttp2 on Linux. nghttp2 implements HTTP/2 framing, HPACK, stream state and flow control under the MIT license. Kotlin owns the application-visible stream state. The C adapter exposes the library's memory interface. The h2c executable attaches it to the shared epoll transport and NativeServe parity routes on loopback. [nghttp2 programmer's guide](https://nghttp2.org/documentation/programmers-guide.html) · [License](https://github.com/nghttp2/nghttp2/blob/master/COPYING)

## Run the probe

On Ubuntu 24.04 x86_64, install the distribution's `libnghttp2-dev` package and run from the repository root with JDK 21.

```sh
./components/nativeserve/gradlew -p components/nativeserve/experiments/http2-nghttp2 \
  -Pnghttp2.headers=/usr/include \
  -Pnghttp2.archive=/usr/lib/x86_64-linux-gnu/libnghttp2.a runReleaseExecutableNative
```

The workflow uses the Ubuntu SDK for each architecture, records the package versions, builds both binaries with Kotlin/Native and checks the corresponding executable on each Linux runner.

Use `linkH2cReleaseExecutableNative` with the same SDK parameters to build the socket executable. Run it on loopback and use a curl build with HTTP/2 support.

```sh
components/nativeserve/experiments/http2-nghttp2/build/bin/native/h2cReleaseExecutable/nativeserve-h2c.kexe 8080
curl --http2-prior-knowledge -fsS http://127.0.0.1:8080/users/123
```

The h2c preview handles bodyless HTTP/2 requests and buffered text and JSON responses. It uses one read range and one write range per connection and admits at most eight streams. SIGTERM stops accepting and queues GOAWAY before draining existing streams to the configured deadline. The executable is a loopback test for prior knowledge. The production deployment milestone covers TLS with ALPN.

## Boundary and ownership

| Operation | Ownership and progress |
| --- | --- |
| Receive bytes | Borrow one pinned Kotlin input range until nghttp2 returns. Incomplete frames can span calls. Copy header values and data slices needed by the Kotlin caller before the callback returns. |
| Deliver request events | Surface headers, data, remote end and stream close with stream IDs. Keep handler and routing state in Kotlin. Enforce the configured application header and body limits before accepting a production stream. |
| Apply backpressure | Return flow-control credit after the application consumes data. The consumer can delay credit while retaining its bounded slice. Multiplexed streams also share the connection window. |
| Send bytes | Pull bounded output into caller-owned storage. The C adapter retains the range returned by nghttp2 across short pulls and only advances the library once it can release that range. A transport can lease the caller buffer until its write completes. |
| Finish or cancel | Handle remote EOF and stream close separately. Release an application's response source on normal stream closure or reset. Dispose the native session before dropping its Kotlin callback reference. |

The probe checks two streams sharing a session, fragmentation on each memory input, bounded output pulls, response data and an explicit `RST_STREAM` cancellation. A 96 KiB response stalls until the receiver returns credit, while a separate small stream completes. The test driver counts wire bytes and input fragments. These counts describe the exercised boundary. Throughput and latency require the matched workload in [#81](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/81).

For [#79](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/79), extend the socket adapter to request and response sources, then exercise resource limits and multiplexed traffic over the real transport. Treat TLS and ALPN as the protocol-selection boundary in [#80](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/80). For JVM integrations, existing Ktor/Netty and Spring WebFlux/Reactor Netty engines can own HTTP/2 framing, leaving application handlers on the portable side of the adapter. [Ktor HTTP/2](https://ktor.io/docs/server-http2.html) · [Spring WebFlux server support](https://docs.spring.io/spring-boot/reference/web/reactive.html)
