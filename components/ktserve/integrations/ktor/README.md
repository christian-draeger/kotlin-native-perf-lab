# ktServe Ktor engine

Select ktServe by passing its `KtServe` engine factory to Ktor's `embeddedServer`. Use the same application module for CIO, Netty, or ktServe.

```kotlin
import io.github.christian_draeger.ktserve.ktor.KtServe

embeddedServer(KtServe, host = "127.0.0.1", port = 8080) {
    routing { get("/health") { call.respondText("ok") } }
}.start(wait = true)
```

The [independent Gradle starter](../../examples/ktor-jvm) resolves `io.github.christian-draeger:ktserve-ktor` from this checkout with a Gradle composite. Run it from the repository root on JDK 21.

```sh
./components/ktserve/gradlew -p components/ktserve/examples/ktor-jvm run
curl -fsS http://127.0.0.1:8080/health
```

## Compatibility

| Area | Supported scope |
| --- | --- |
| Toolchain | Kotlin 2.4.21, Ktor 3.6.1, Linux x86_64 and ARM64 Kotlin/Native, and JDK 21 with JVM NIO. |
| Listener | One fixed-port IPv4 HTTP/1.x connector. Configure server limits with `KtServeEngine.Configuration.limits`. |
| Ktor features | Standard routing, receive/send pipeline, request fields and query parameters, typed JSON through ContentNegotiation, repeated response fields, and incremental request and response bodies. |
| Cancellation | ktServe cancels a suspended call after peer EOF or a handler deadline. A response producer is tied to connection lifetime. |
| Current limits | HTTP/2, HTTPS connectors, WebSocket upgrades, Unix sockets, port zero and peer address metadata are outside this adapter's supported surface. Use a private ingress for TLS termination. |

The engine owns a dedicated I/O thread and uses Kotlin coroutines for application dispatch. The existing ktServe connection, body, header and shutdown limits apply. The Ktor channel adapters use bounded byte channels and propagate disconnect cancellation to the call or response producer.

## Run the same application under different engines

```sh
project=components/ktserve/integrations/ktor
./components/ktserve/gradlew -p "$project" jvmTest
./components/ktserve/gradlew -p "$project" linkReleaseExecutableNative
python3 "$project/smoke.py" "$project/build/bin/native/releaseExecutable/ktserve-ktor-example.kexe"
```

The JVM suite exercises ktServe, CIO and Netty. The Linux native fixture exercises both ktServe and CIO with routing, typed JSON, an application header, body streaming and application stop. The separate controlled comparisons are tracked in [#39](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/39) and [#42](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/42).
