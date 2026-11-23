# NativeServe

HTTP framework for Kotlin/Native.

NativeServe provides a Kotlin routing API and runs on Linux x86_64 and ARM64. Its I/O core uses the reactor pattern with nonblocking Linux sockets and epoll. The benchmark application uses the public API for the Ktor and Axum parity endpoints.

## Run the example

On Linux x86_64 with JDK 21:

```sh
cd components/nativeserve
./gradlew runReleaseExecutableNative
```

In another terminal:

```sh
curl -fsS http://127.0.0.1:8080/health
curl -fsS http://127.0.0.1:8080/users/123
```

Set `PORT` to change the example port. CI cross-compiles the ARM64 executable on x86_64 and runs the smoke checks on the ARM64 runner. [Kotlin/Native host support](https://kotlinlang.org/docs/native-target-support.html#hosts)

## Develop on Apple Silicon

With a local OrbStack or Docker-compatible Linux runtime and a JDK supported by the Gradle wrapper, run this from the repository root:

```sh
./scripts/dev-nativeserve.sh
```

Gradle builds the Linux ARM64 release on macOS. The script runs it in Ubuntu 24.04 pinned by image digest and forwards its HTTP port to Mac localhost. The first run downloads the pinned Gradle and Kotlin toolchains and the runtime image. Later builds reuse the normal Gradle and Kotlin caches.

Check startup from another terminal with `curl -fsS http://127.0.0.1:8080/health`. Stop the foreground server with Ctrl+C and rerun the same command after editing the application. Set `PORT=8081` before the command to use another host port when 8080 is occupied. Use a local Docker context so the runtime can mount the compiled file. This workflow is for development and exploratory measurements.

Run parser and routing tests directly on macOS with `./components/nativeserve/gradlew -p components/nativeserve jvmTest`. Run the shared HTTP checks against the running container with `./scripts/smoke.sh http://127.0.0.1:8080`.

## Write a handler

```kotlin
import dev.perflab.nativeserve.*
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

The first implementation accepts origin-form HTTP/1.0 and HTTP/1.1 requests and exposes GET routes. It accepts empty request bodies. The example binds to loopback using a numeric IPv4 address. `ServerLimits` controls the connection cap, head and response size, idle time, per-head deadline, and shutdown drain time.

The example handles Ctrl+C and SIGTERM by closing the listener and draining queued output for at most two seconds by default. An embedded application can pass a `stopRequested` callback to `serve` and retain control of its own signal handlers. The reactor polls that callback while it owns the listener.

## Ownership and lifetime

| Resource | Owner and release |
| --- | --- |
| Listening socket | `serve` closes it if setup fails. The reactor closes it when shutdown begins or the loop exits. |
| epoll descriptor and event storage | The reactor closes the descriptor in `finally`. Event storage lives in the loop's `memScoped`. |
| Accepted descriptor | The reactor owns one client state per registration token. Registration failure, peer close, failed I/O or expiry closes the descriptor. The loop closes any remaining clients in `finally`. |
| Request storage | One fixed Kotlin byte array per client. It keeps unread pipelined bytes and is pinned only during `recv`. |
| Output storage | Each client retains at most one encoded response and its current write offset. The array is pinned only during `send`. Write interest is enabled while output remains. |

The protocol limits bound retained socket and buffer state. Application handler allocations are under the caller's control. Run portable parser and routing tests with `./gradlew jvmTest`. The shared smoke workflow covers the release executable on both architectures.

Terminate TLS at an HTTPS ingress and forward HTTP/1.1 to the reactor on a private interface. Set ingress request size and timeout limits that match the backend's `ServerLimits`. Route handlers run on the I/O loop so a handler should return promptly.

The Linux resource suite uses a separate release fixture with short deadlines and large bodies to check slow and disconnected clients, descriptor caps and retained heap. To run it locally from the repository root on Linux x86_64:

```sh
./components/nativeserve/gradlew -p components/nativeserve linkStressReleaseExecutableNative
python3 scripts/tests/reactor_stress.py components/nativeserve/build/bin/native/stressReleaseExecutable/reactor-stress-fixture.kexe
```

[Project roadmap](../../ROADMAP.md)

The source module lives in `components/nativeserve/`. Benchmark records use the stable `kotlin-reactor` identifier, which the dashboard displays as NativeServe across existing and new series. The release executable is `nativeserve.kexe`, and the public Kotlin package is `dev.perflab.nativeserve`.
