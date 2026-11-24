# ktServe Spring WebFlux adapter

Run an existing Spring reactive `HttpHandler` on the ktServe JVM transport.

```kotlin
val handler = RouterFunctions.toHttpHandler(routes)
serveWebFlux(handler, host = "127.0.0.1", port = 8080)
```

The adapter targets Spring Framework 7.1.0 and the Spring Boot 4.2.0 dependency set on JDK 21. It supports HTTP/1.x status and fields, cookies, request body publishers, response body publishers, and cancellation. It accepts one fixed-port IPv4 listener. Applications terminate TLS at an ingress. The bridge currently exposes the configured local address and leaves the remote address unavailable.

The ktServe connection and response limits apply to each exchange. Request chunks are owned arrays that Spring receives through non-pooled buffers. Outbound reactive buffers are copied and released before entering the coroutine response queue. The publisher bridge requests one buffer at a time and the coroutine writer pauses on a full queue. Closing the connection cancels the reactive subscription.

The reactive pipeline runs off the I/O loop. Application code can schedule blocking calls on an appropriate Reactor scheduler. The adapter sends flush groups in order through the same bounded writer.

The socket tests run a shared WebFlux module on Reactor Netty and ktServe. They also check release of pooled output and cancellation after disconnect.

```sh
./components/ktserve/gradlew -p components/ktserve/integrations/webflux test
```

Controlled engine comparison is tracked in [#45](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/45).
