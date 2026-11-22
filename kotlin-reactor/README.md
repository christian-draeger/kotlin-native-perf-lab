# Kotlin Reactor reference server

The reference server exposes a Kotlin routing API and runs on Linux x86_64 and ARM64. The benchmark application uses that same API for the Ktor and Axum parity endpoints.

## Run the example

On Linux x86_64 with JDK 21:

```sh
cd kotlin-reactor
./gradlew runReleaseExecutableNative
```

In another terminal:

```sh
curl -fsS http://127.0.0.1:8080/health
curl -fsS http://127.0.0.1:8080/users/123
```

Set `PORT` to change the example port. CI cross-compiles the ARM64 executable on x86_64 and runs the smoke checks on the ARM64 runner. [Kotlin/Native host support](https://kotlinlang.org/docs/native-target-support.html#hosts)

## Write a handler

```kotlin
import dev.perflab.reactor.*
import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Int, val name: String)

fun main() {
    serve(port = 8080) {
        get("/health") { text("ok") }
        get("/users/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            json(User(id, "example"))
        }
    }
}
```

Exact routes take precedence over parameter patterns. Parameter patterns with overlapping matches use declaration order. A route can read a typed integer path parameter or the raw query. The transport sets the content length and the connection policy. Handler exceptions produce HTTP 500.

The first implementation accepts origin-form HTTP/1.0 and HTTP/1.1 requests and exposes GET routes. It accepts empty request bodies. The example binds to loopback using a numeric IPv4 address. `ServerLimits` controls the connection cap, head and response size, idle time, and per-head deadline. Terminating the reference process releases its Linux descriptors.

## Ownership and lifetime

| Resource | Owner and release |
| --- | --- |
| Listening socket | `serve` closes it after the reactor returns or fails. |
| epoll descriptor and event storage | The reactor closes the descriptor in `finally`. Event storage lives in the loop's `memScoped`. |
| Accepted descriptor | The reactor owns one client state per registration token. Registration failure, peer close, failed I/O or expiry closes the descriptor. The loop closes any remaining clients in `finally`. |
| Request storage | One fixed Kotlin byte array per client. It keeps unread pipelined bytes and is pinned only during `recv`. |
| Output storage | Each client retains at most one encoded response and its current write offset. The array is pinned only during `send`. Write interest is enabled while output remains. |

The protocol limits bound retained socket and buffer state. Application handler allocations are under the caller's control. Run portable parser and routing tests with `./gradlew jvmTest`. The shared smoke workflow covers the release executable on both architectures.

[Project roadmap](../ROADMAP.md)
