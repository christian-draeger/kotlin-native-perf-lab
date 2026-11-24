# NativeServe

HTTP framework for Kotlin/Native.

NativeServe provides a Kotlin routing API on Linux x86_64 and ARM64 and on Apple Silicon macOS. Its event loop uses nonblocking sockets with epoll on Linux and kqueue on macOS. The benchmark application uses the public API for the Ktor and Axum parity endpoints.

Set `NATIVESERVE_WORKERS=2` on the benchmark executable to try the experimental worker configuration. The process-wide connection limit is divided among workers. Each worker runs its own epoll loop on a shared listener. This option is available for evaluating the benchmark application.

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

The Ubuntu smoke workflow publishes verified snapshot archives for Linux x86_64 and ARM64. In the directory containing the downloaded archive and its checksum, run `sha256sum -c <archive>.sha256`, extract the archive, and start `./nativeserve`. The included compatibility file describes the target and executable interpreter.

## Develop on Apple Silicon

With JDK 21 and a configured full Xcode installation, run the separate consumer directly on macOS from the repository root.

```sh
./components/nativeserve/gradlew -p components/nativeserve/examples/hello -Pbenchmark.targetOs=macos runReleaseExecutableNative
```

Check it with `curl -fsS http://127.0.0.1:8080/health`. Use `runDebugExecutableNative` for a local debug build. If Command Line Tools are selected, prefix the command with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` to select the installed Xcode for this build. The macOS smoke workflow verifies releases on macOS 15 ARM64 and publishes checksummed `nativeserve-snapshot-*-macos-arm64.tar.gz` artifacts. A prebuilt archive runs with `./nativeserve` after `shasum -a 256 -c <archive>.sha256` and extraction. The native development target is Apple Silicon.

The Linux container mode below reproduces the Linux deployment backend on the same Mac.

With a local OrbStack or Docker-compatible Linux runtime and a JDK supported by the Gradle wrapper, run this from the repository root:

```sh
./scripts/dev-nativeserve.sh
```

Gradle builds the Linux ARM64 release on macOS. The script runs it in Ubuntu 24.04 pinned by image digest and forwards its HTTP port to Mac localhost. The first run downloads the pinned Gradle and Kotlin toolchains and the runtime image. Later builds reuse the normal Gradle and Kotlin caches.

Check startup from another terminal with `curl -fsS http://127.0.0.1:8080/health`. Stop the foreground server with Ctrl+C and rerun the same command after editing the application. Set `PORT=8081` before the command to use another host port when 8080 is occupied. Use a local Docker context so the runtime can mount the compiled file. This workflow is for development and exploratory measurements.

Run parser and routing tests directly on macOS with `./components/nativeserve/gradlew -p components/nativeserve jvmTest`. Run the shared HTTP checks against the running container with `./scripts/smoke.sh http://127.0.0.1:8080`.

## Develop on the JVM

The [JVM starter](examples/hello-jvm) runs the same routing DSL and HTTP core with the JDK 21 NIO transport. From the repository root:

```sh
./components/nativeserve/gradlew -p components/nativeserve/examples/hello-jvm run
```

Check it with `curl -fsS http://127.0.0.1:8080/health`. The starter uses a Gradle composite to resolve `dev.perflab:nativeserve` from the checkout. Both Linux CI architectures run the portable tests, real JVM socket tests and the separate Gradle application. `NATIVESERVE_BACKEND=nio` explicitly selects the JVM backend.

## Write a handler

The [hello example](examples/hello) is a separate Gradle build that consumes `dev.perflab:nativeserve` from this checkout. On Linux x86_64 with JDK 21, run the example from the repository root.

```sh
./components/nativeserve/gradlew -p components/nativeserve/examples/hello runReleaseExecutableNative
```

Open `http://127.0.0.1:8080/` to see the greeting. The example uses the same Kotlin version and Gradle wrapper as NativeServe. Linux CI builds and tests the example release executable on x86_64 and ARM64.

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

For a body with a known byte length, return `streamResponse` from a route. NativeServe sends the response head and pulls up to 16 KiB at a time as the connection writes progress.

```kotlin
get("/download") {
    streamResponse(48 * 1024, "application/octet-stream", ResponseSource { buffer, limit ->
        buffer.fill('x'.code.toByte(), toIndex = limit)
        limit
    })
}
```

Create a new source for each response. Return promptly from `read` and `close`, which run on the owning I/O loop. Write up to `limit` bytes starting at index zero and release any references to the buffer before returning. Override `close` to release source resources. NativeServe takes ownership when the handler returns the response and closes it after the final write completes or the response is cancelled. `ServerLimits.responseBodyBytes` also applies to streamed bodies. If the source ends early or fails after the head is sent, the server closes the connection so the client can detect the incomplete body.

For an HTTP/1.1 stream with an unknown length, use `streamResponse(contentType, source)` and return `-1` at end of input. NativeServe applies chunked framing and terminates the stream when it reaches EOF. The response size limit applies to the body bytes. An unknown-length response to an HTTP/1.0 request returns HTTP 500 and closes the source. [HTTP/1.1 message framing](https://www.rfc-editor.org/rfc/rfc9112.html#name-chunked-transfer-coding)

The first implementation accepts origin-form HTTP/1.0 and HTTP/1.1 requests and exposes GET routes. It accepts empty request bodies. The example binds to loopback using a numeric IPv4 address. `ServerLimits` controls the connection cap, head and response size, idle time, per-head deadline, and shutdown drain time.

The example handles Ctrl+C and SIGTERM by closing the listener and draining queued output for at most two seconds by default. An embedded application can pass a `stopRequested` callback to `serve` and retain control of its own signal handlers. The reactor polls that callback while it owns the listener.

## Ownership and lifetime

| Resource | Owner and release |
| --- | --- |
| Listening socket | The Linux backend closes it if setup fails or the HTTP loop stops accepting connections. |
| Poll descriptor and event storage | epoll or kqueue owns its descriptor. Event storage lives in the scope of `serve`. |
| Accepted descriptor | The backend tracks each descriptor by its connection ID and closes any remaining sockets when `serve` exits. The HTTP loop owns the HTTP state for each connection. |
| Request storage | One fixed Kotlin byte array per client. It keeps unread pipelined bytes and is pinned only during `recv`. |
| Output storage | Each client retains at most one encoded response or a response head with a reusable chunk buffer of up to 16 KiB. The stream buffer is reused after the preceding write completes. The array is pinned only during `send`. The poller checks for write readiness while output is pending. |

The protocol limits bound retained socket and buffer state. Application handler allocations are under the caller's control. Run portable parser and routing tests with `./gradlew jvmTest`. The shared smoke workflow covers the release executable on both architectures.

Terminate TLS at an HTTPS ingress and forward HTTP/1.1 to the reactor on a private interface. Set ingress request size and timeout limits that match the backend's `ServerLimits`. Route handlers run on the I/O loop so a handler should return promptly.

The native resource suite uses a separate release fixture with short deadlines and large bodies to check slow and disconnected clients, descriptor caps and retained heap. To run it locally from the repository root on Linux x86_64:

```sh
./components/nativeserve/gradlew -p components/nativeserve linkStressReleaseExecutableNative
python3 scripts/tests/reactor_stress.py components/nativeserve/build/bin/native/stressReleaseExecutable/reactor-stress-fixture.kexe
```

On macOS, add `-Pbenchmark.targetOs=macos` to the Gradle command and use the fixture under `components/nativeserve/build/macos/bin/native/stressReleaseExecutable/`.

[Project roadmap](../../ROADMAP.md)

The source module lives in `components/nativeserve/`. Benchmark records use the stable `kotlin-reactor` identifier, which the dashboard displays as NativeServe across existing and new series. The release executable is `nativeserve.kexe`, and the public Kotlin package is `dev.perflab.nativeserve`.
