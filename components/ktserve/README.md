# ktServe

HTTP framework for Kotlin/Native and the JVM.

ktServe provides a Kotlin routing API on Linux x86_64 and ARM64 and on Apple Silicon macOS. Its event loop uses nonblocking sockets with epoll on Linux and kqueue on macOS. The benchmark application uses the public API for the Ktor and Axum parity endpoints.

## Run the example

On Linux x86_64 with JDK 21:

```sh
cd components/ktserve
./gradlew runReleaseExecutableNative
```

In another terminal:

```sh
curl -fsS http://127.0.0.1:8080/health
curl -fsS http://127.0.0.1:8080/users/123
```

Set `PORT` to change the example port. CI cross-compiles the ARM64 executable on x86_64 and runs the smoke checks on the ARM64 runner. [Kotlin/Native host support](https://kotlinlang.org/docs/native-target-support.html#hosts)

The Ubuntu smoke workflow publishes verified snapshot archives for Linux x86_64 and ARM64. In the directory containing the downloaded archive and its checksum, run `sha256sum -c <archive>.sha256`, extract the archive, and start `./ktserve`. The included compatibility file describes the target and executable interpreter.

## Choose a Linux worker mode

By default, Linux `serve { … }` starts two epoll loops when the calling thread's affinity, the visible cgroup v2 CPU quota and the connection limit allow it. It starts one loop when the budget is smaller or cannot be verified. The eager count is chosen at startup and logged as `ktServe worker_policy=eager workers=<count>`.

| Mode | Kotlin API | Environment override for implicit `serve` |
| --- | --- | --- |
| Eager | `serve { … }` or `serve(workerPolicy = LinuxWorkerPolicy.EAGER) { … }` | `KTSERVE_ADAPTIVE_WORKERS=eager` |
| Fixed | `serve(workers = 1) { … }`, or another positive count within the connection limit | `KTSERVE_WORKERS=1`, or another count |
| Warm standby | `serve(workerPolicy = LinuxWorkerPolicy.STANDBY) { … }` | `KTSERVE_ADAPTIVE_WORKERS=standby` |

Explicit Kotlin arguments select the mode for that server. Eager and standby share one listener, one process connection limit and one blocking-handler budget. Fixed worker counts retain their per-loop blocking-handler limits.

**Concurrency on upgrade.** With two loops, shared handlers and observers can be invoked concurrently. Observer lifecycle events, connection counts and exchange identifiers are local to a loop. Keep application state thread-safe or select `workers = 1` for serialized inline handlers. The [qualified ingress profile](deployment/PRODUCTION.md) explicitly selects one worker.

Standby opens the second worker's transport at startup with accepts paused. Two samples of at least 100 ms at 75% or more primary thread CPU, plus recent traffic on multiple connections, wake it. Without exchange observation it can transfer recently active connections between complete exchanges. With an observer it accepts new connections and keeps each existing exchange observer on its original loop. After at least five seconds of quiet and once its peers close, the second loop parks for the next burst. The [worker comparison](https://github.com/christian-draeger/ktserve/actions/runs/45694786102) covers both policies, fixed counts, connection survival and tail latency.

The original cold-start worker remains a benchmark control. Set `KTSERVE_ADAPTIVE_WORKERS=1`, optionally with `KTSERVE_ADAPTIVE_HANDOFF=1`, to reproduce it.

## Develop on Apple Silicon

With JDK 21 and a configured full Xcode installation, run the separate consumer directly on macOS from the repository root.

```sh
./components/ktserve/gradlew -p components/ktserve/examples/hello -Pbenchmark.targetOs=macos runReleaseExecutableNative
```

Check it with `curl -fsS http://127.0.0.1:8080/health`. Use `runDebugExecutableNative` for a local debug build. If Command Line Tools are selected, prefix the command with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` to select the installed Xcode for this build. The macOS smoke workflow verifies releases on macOS 15 ARM64 and publishes checksummed `ktserve-snapshot-*-macos-arm64.tar.gz` artifacts. A prebuilt archive runs with `./ktserve` after `shasum -a 256 -c <archive>.sha256` and extraction. The native development target is Apple Silicon.

The Linux container mode below reproduces the Linux deployment backend on the same Mac.

With a local OrbStack or Docker-compatible Linux runtime and a JDK supported by the Gradle wrapper, run this from the repository root:

```sh
./scripts/dev-ktserve.sh
```

Gradle builds the Linux ARM64 release on macOS. The script runs it in Ubuntu 24.04 pinned by image digest and forwards its HTTP port to Mac localhost. The first run downloads the pinned Gradle and Kotlin toolchains and the runtime image. Later builds reuse the normal Gradle and Kotlin caches.

Check startup from another terminal with `curl -fsS http://127.0.0.1:8080/health`. Stop the foreground server with Ctrl+C and rerun the same command after editing the application. Set `PORT=8081` before the command to use another host port when 8080 is occupied. Use a local Docker context so the runtime can mount the compiled file. This workflow is for development and exploratory measurements.

Run parser and routing tests directly on macOS with `./components/ktserve/gradlew -p components/ktserve jvmTest`. Run the shared HTTP checks against the running container with `./scripts/smoke.sh http://127.0.0.1:8080`.

## Develop on the JVM

The [JVM starter](examples/hello-jvm) runs the same routing DSL and HTTP core with the JDK 21 NIO transport. From the repository root:

```sh
./components/ktserve/gradlew -p components/ktserve/examples/hello-jvm run
```

Check it with `curl -fsS http://127.0.0.1:8080/health`. The starter uses a Gradle composite to resolve `codes.draeger:ktserve` from the checkout. Both Linux CI architectures run the portable tests, real JVM socket tests and the separate Gradle application. `KTSERVE_BACKEND=nio` explicitly selects the JVM backend.

The [Maven release guide](RELEASING.md) covers the versioned library and how to test it from a separate Gradle project.

## Use ktServe with a framework

- [Ktor engine](integrations/ktor/README.md) on Kotlin/Native Linux and the JVM.
- [Spring Boot starter](integrations/spring-boot-starter/README.md) for an opt-in WebFlux server on the JVM.
- [Spring WebFlux adapter](integrations/webflux/README.md) for a reactive `HttpHandler` on the JVM.

## Experiments

- [Native CLI for static directories and Kotlin apps](experiments/cli/README.md)
- [HTTP/2 with nghttp2 and TLS ingress](experiments/http2-nghttp2/README.md)
- [Linux io_uring](experiments/linux-io-uring/README.md)
- [Windows x64 IOCP](experiments/windows-iocp/README.md)

## Write a handler

The [hello example](examples/hello) is a separate Gradle build that consumes `codes.draeger:ktserve` from this checkout. On Linux x86_64 with JDK 21, run the example from the repository root.

```sh
./components/ktserve/gradlew -p components/ktserve/examples/hello runReleaseExecutableNative
```

Open `http://127.0.0.1:8080/` to see the greeting. The example uses the same Kotlin version and Gradle wrapper as ktServe. Linux CI builds and tests the example release executable on x86_64 and ARM64.

```kotlin
import codes.draeger.ktserve.*
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

### Typed routes

Use an `api` group to return Kotlin values directly and to decode typed JSON requests. The group prefix is optional.

```kotlin
import codes.draeger.ktserve.*
import kotlinx.serialization.Serializable

@Serializable
data class CreateUser(val name: String)

@Serializable
data class User(val id: Int, val name: String)

fun main() = serve {
    api("/api") {
        get("/health") { "ok" }
        get("/users/42") { User(42, "Ada") }
        get("/health-json", returns = json()) { "ok" }
        post("/users", status = 201) { input: CreateUser -> User(42, input.name) }
    }
}
```

Non-null scalar values use UTF-8 plain text. Serializable objects and lists use JSON. Use `returns = json()` to send a scalar as JSON or return a `Response` when the status, headers, or format depend on the request. Apply the Kotlin Serialization compiler plugin to the application module for its `@Serializable` classes.

Typed request handlers check the JSON media type and use the existing request size and timeout limits. A mismatched JSON body returns 400, an unsupported media type returns 415, and a failed validator returns 422. The typed errors use `application/problem+json`. Use `body.receiveJson<T>()` inside a regular suspending route when you want to manage status and error handling yourself.

[Validation and OpenAPI](extensions/README.md) use the same typed routes. Install validation once for annotated request types and optionally override a validator on a route or an enclosing API group. The OpenAPI extension uses the resolved media types, statuses, input and output contracts during startup.

### Request and response helpers

Use `request.header("accept")` for the first matching request value or `request.headers()` for the ordered header fields, including duplicates. Use `response(bytes, status, contentType)` for a pre-encoded body and `.withHeaders(HttpField("Cache-Control", "no-store"))` to add response headers. ktServe validates response fields and sets framing and connection headers. `ServerLimits.responseHeaderBytes` bounds application-added fields and the Content-Type field.

Use `request.queryParameter("q")` for the first decoded query value and `request.queryParameters("tag")` for repeated values in order. Lookup decodes UTF-8 percent escapes on demand and treats `+` as a literal plus. A bare name has an empty value, and pairs with invalid escapes or UTF-8 are skipped. Use `redirect("/new-path")` for a 308 response or pass 301, 302, 303, or 307 explicitly.

Use `getSuspending` when an HTTP/1.x handler calls a suspending API.

```kotlin
getSuspending("/profile/{id}") { request ->
    val id = request.pathInt("id") ?: return@getSuspending badRequest()
    json(profileStore.load(id))
}
```

Suspending handlers run on `Dispatchers.Default` and can run concurrently across connections. Each connection has one active handler and sends pipelined responses in order. A peer EOF while the handler is pending, `ServerLimits.handlerMillis`, or the end of the shutdown drain cancels the coroutine. Cancellation is cooperative. Applications should use cancellable I/O and release their own resources in `finally`. The experimental h2c engine returns HTTP 501 for these routes.

Use `getBlocking` for a GET that must invoke a blocking API. It runs on the shared coroutine I/O dispatcher with up to `ServerLimits.blockingHandlers` admitted calls (eight by default). Eager and warm standby share those slots across loops. Fixed workers have a limit per loop. When all slots are taken, another blocking route returns HTTP 503 immediately. Timeout, disconnect, or the end of the shutdown drain releases the connection. A blocking operation can continue until its own I/O returns and retains its slot until then. Set timeouts on the blocking client.

`ServerLimits.responseMillis` starts when the response is queued and applies to the complete write, including any pauses in a streaming producer. It defaults to 30 seconds and runs independently of the idle timer. On expiry the server closes the connection and disposes of the pending response. Before headers reach the client this appears as a closed connection. After headers are sent it appears as a truncated response. Deadline sweeps are bounded by the smaller of the configured response limit and 250 ms. Short synchronous handlers and synchronous stream callbacks still run on the event loop. Use suspending I/O, `getBlocking`, or a suspending response producer for work that can take longer.

Framework adapters can use `application { request, body -> … }` to own routing on an HTTP/1.x listener. `body.readChunk()` suspends until a chunk or the end of the body arrives. Each returned array belongs to the application. While the handler is slow, ktServe queues one body chunk and pauses connection reads once the bounded input fills. A response returned before the request body finishes closes the connection after writing. HEAD responses keep their known content length and omit the payload. The request size, request body timeout and handler timeout apply to the whole exchange.

For a body with a known byte length, return `streamResponse` from a route. ktServe sends the response head and pulls up to 16 KiB at a time as the connection writes progress.

```kotlin
get("/download") {
    streamResponse(48 * 1024, "application/octet-stream", ResponseSource { buffer, limit ->
        buffer.fill('x'.code.toByte(), toIndex = limit)
        limit
    })
}
```

Create a new source for each response. Return promptly from `read` and `close`. Writes pull data on the owning I/O loop. A response returned by a cancelled coroutine may be closed on the handler dispatcher. Write up to `limit` bytes starting at index zero and release any references to the buffer before returning. Override `close` to release source resources. ktServe takes ownership when the handler returns the response and closes it after the final write completes or the response is cancelled. `ServerLimits.responseBodyBytes` also applies to streamed bodies. If the source ends early or fails after the head is sent, the server closes the connection so the client can detect the incomplete body.

For an HTTP/1.1 stream with an unknown length, use `streamResponse(contentType, source)` and return `-1` at end of input. ktServe applies chunked framing and terminates the stream when it reaches EOF. The response size limit applies to the body bytes. An unknown-length response to an HTTP/1.0 request returns HTTP 500 and closes the source. [HTTP/1.1 message framing](https://www.rfc-editor.org/rfc/rfc9112.html#name-chunked-transfer-coding)

Use `streamSuspending` to generate a response with suspending APIs. Its writer copies at most 16 KiB per chunk and suspends when the transfer queue fills. Acquire resources inside the producer and release them in `finally`. ktServe cancels a producer when the response completes, the connection closes, or the shutdown drain ends. The experimental h2c server returns HTTP 501 for suspending producers.

The HTTP/1.x origin accepts origin-form and HTTP(S) absolute-form request targets. Paths and queries preserve percent escapes. An empty path in an absolute target becomes `/`. For absolute targets, `header("host")` and `headers()` expose the validated authority from the target. Host fields accept ASCII registered names, IPv4 addresses and bracketed IPv6 addresses with an optional decimal port. HTTP/1.1 requires one Host field. [HTTP/1.1 request targets](https://www.rfc-editor.org/rfc/rfc9112.html#section-3.2)

The parser validates the wire structure as bytes before decoding application values. Each request owns a compact copy of its fields, and lookup decodes values on demand. `get` handles requests with empty bodies. `postStreaming` consumes a POST with a valid `Content-Length` or HTTP/1.1 chunked framing through a `RequestBodySink`. `accept` sees one borrowed slice at a time and must return promptly. `complete` returns the response after the full body arrives. ktServe calls `close` once per opened sink, including on disconnect and timeout. The input buffer bounds each slice, and `requestBodyBytes` and `requestBodyMillis` bound the full body. `headerBytes` also caps total chunk-size lines and trailers. ktServe validates trailers before completing the body, and the handler receives only the body bytes. An `Expect` header returns HTTP 417. The example binds to loopback using a numeric IPv4 address.

The example handles Ctrl+C and SIGTERM by closing the listener and draining queued output for at most two seconds by default. An embedded application can pass a `stopRequested` callback to `serve` and retain control of its own signal handlers. The reactor polls that callback while it owns the listener.

## Serve static files

Call `staticFiles` inside `serve` for each directory. Files in `public` are served at `/`, and files in `docs` are served under `/docs/`. Dynamic routes can use the same listener.

```kotlin
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.staticFiles
import codes.draeger.ktserve.text

fun main() = serve(limits = ServerLimits(responseBodyBytes = 1024 * 1024)) {
    staticFiles("public")
    staticFiles("docs", at = "/docs", cacheControl = "no-cache")
    get("/status") { text("ok") }
}
```

ktServe snapshots each directory during configuration. Directory URLs ending in `/` serve `index.html`. GET and HEAD are handled by the router, and duplicate GET paths fail configuration. The snapshot skips hidden entries and symlinks, with caps of 1 MiB per file, 16 MiB of file contents and 4,096 entries per directory tree, and 16 levels of subdirectories. `ServerLimits.responseBodyBytes` controls the maximum response size. Restart the application to pick up file changes.

An index directory requested without its trailing slash gets a 308 redirect that preserves the query. An explicit GET route at that exact path takes precedence. Each snapshot assigns an opaque ETag to every file. Matching `If-None-Match` requests receive 304. A `cacheControl` value applies to successful representations and revalidation responses for that mount. [HTTP validators](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2) · [Cache-Control](https://www.rfc-editor.org/rfc/rfc9111.html#section-5.2)

A static GET accepts one closed, open-ended, or suffix byte range. A satisfiable range returns 206 and an unsatisfiable range returns 416. Multiple ranges fall back to the full response. A matching strong ETag in `If-Range` allows partial delivery. HEAD uses the full representation length. [Byte ranges](https://www.rfc-editor.org/rfc/rfc9110.html#section-14.2)

The JVM loader requires the default filesystem provider to implement `SecureDirectoryStream`. The [CLI experiment](experiments/cli/README.md#serve-a-directory) calls the same API on Kotlin/Native.

`serve { }` starts with an empty route table. The bundled benchmark application registers `/health`, `/json`, and `/users/{id}` explicitly. [Benchmark routes](src/nativeShared/kotlin/codes/draeger/ktserve/example/Example.kt)

## Standalone REST routes

Register a bounded, suspending handler with `route("PUT", "/items/{id}") { request, body -> … }`.
Use `body.readChunk()` to consume the body incrementally. These routes can share the listener
with `get` and `postStreaming`. A matching general route takes precedence for its method and
path. A HEAD request uses an explicit HEAD route if present, then falls back to GET with the
response body suppressed. On a known path, automatic origin-form OPTIONS returns 204 and
`Allow`, and a method mismatch returns 405 with the same method list. Other paths return 404.
The experimental h2c listener requires legacy GET and streaming POST routes. It rejects configuration with general routes, early request policies or request hooks.

Register short synchronous `beforeEach` checks for headers or other request metadata.
Checks run in registration order before routing and body consumption. Return `null` to
continue or a response to reject the exchange. A rejection with an unread body closes
the connection after writing the response. For authenticated deployments, enforce the
identity at a trusted ingress and restrict direct access to the origin. The separate
[REST JVM consumer](examples/rest-jvm/) shows a verified identity assertion and bounded
JSON handling.

## Write a server extension

The preview extension API is available on standalone HTTP/1.x. Register each extension with
an ID and install it inside `serve`.

```kotlin
import codes.draeger.ktserve.*

val applicationHeader = extension("example.application-header") {
    onResponseHeaders { _, headers ->
        headers.set("X-App", "hello")
    }
}

fun main() = serve(port = 8080) {
    install(applicationHeader)
    get("/health") { text("ok") }
}
```

Within each phase, hooks run in installation order. Use `onRequest` for checks before
routing, and `onRoute` to work with the matched route template and path parameters. Both
hooks return `RequestDecision.Continue` or `RequestDecision.Respond(response)`. A response
skips the remaining selection hooks and proceeds to header editing and completion.
Use `onRequestStart` for observation before any request policy can respond early.
Existing `beforeEach` policies run after `onRequest`. An `application` adapter receives
request and response hooks, and provides its own routing.

Hooks run on the owning I/O loop and can run concurrently across loops. Keep callbacks
short and offload I/O. Set typed attributes in request or route hooks and read them in
handlers using `request.attribute(key)`. `onComplete` runs once after a valid request
finishes writing or ends with a terminal outcome. Its duration starts when ktServe accepts
the request headers. Use the same `RequestAttribute<T>` instance for producing and
consuming a value.

The [separate JVM example](examples/hello-jvm) compiles the header extension using the
public API. Run it with `KTSERVE_SAMPLE_HEADERS=1` to see the header on its responses.
See the [optional extension modules](extensions/) for configuration and separate Maven
coordinates for CORS, Bearer, OpenAPI, JVM OpenTelemetry and access logging.

## Observe an HTTP/1.x server

Install a `ServerObserver` with `observe` inside the route configuration. It receives readiness and drain events, the number of active connections, connection admission rejections, and terminal exchange records. A record includes a connection-local request number, status when a response was selected, elapsed time, and the completion or failure reason. The server records request-body, handler, and response deadlines separately. Events omit paths, headers, and bodies.

Callbacks run on the I/O loop. Keep the callback short and offload logging or export to a bounded queue. Use status and outcome for metric labels. Treat request identifiers as log fields. An observer exception leaves the server and its cleanup running.

For an observer that only needs exchange fields, implement `ExchangeObserver`. ktServe passes the fields directly without allocating an exchange event. Override `forLoop()` to provide loop-local state, such as a queue drained by a background writer. A status of zero means the exchange ended before ktServe selected a response.

Run the separate JVM example with `KTSERVE_OBSERVE=1` to exercise its [console observer](examples/hello-jvm/src/main/kotlin/codes/draeger/examples/hello/ConsoleObserver.kt). It exposes `/ready` on the example listener and emits structured exchange logs and cumulative counters. The queue records drops if the logging destination falls behind.

## Ownership and lifetime

| Resource | Owner and release |
| --- | --- |
| Listening socket | The Linux backend closes it if setup fails or the HTTP loop stops accepting connections. |
| Poll descriptor and event storage | epoll or kqueue owns its descriptor. Event storage lives in the scope of `serve`. |
| Accepted descriptor | The backend tracks each descriptor by its connection ID and closes any remaining sockets when `serve` exits. The HTTP loop owns the HTTP state for each connection. |
| Request storage | One fixed Kotlin byte array per client. It keeps unread pipelined bytes and is pinned only during `recv`. |
| Suspended handler | One coroutine and a single-result handoff per active connection. The connection can allocate one additional read buffer of up to 4 KiB to observe EOF or receive pipelined bytes. |
| Output storage | Each client retains at most one encoded response or a response head with a reusable chunk buffer of up to 16 KiB. The stream buffer is reused after the preceding write completes. The array is pinned only during `send`. The poller checks for write readiness while output is pending. |

The protocol limits bound retained socket and buffer state. Application handler allocations are under the caller's control. Run portable parser and routing tests with `./gradlew jvmTest`. The shared smoke workflow covers the release executable on both architectures.

Terminate TLS at an HTTPS ingress and forward HTTP/1.1 to the reactor on a private interface. The [loopback deployment recipe](deployment/http1/README.md) defines compatible limits, forwarding policy, and an upgrade and rollback procedure. Synchronous route handlers run on the I/O loop and should return promptly.

The [native CLI HTTPS experiment](experiments/cli/README.md#serve-https-directly-on-linux) can also terminate TLS on a single Linux reactor using caller-provided certificates.

The native resource suite uses a separate release fixture with short deadlines and large bodies to check slow and disconnected clients, descriptor caps and retained heap. To run it locally from the repository root on Linux x86_64:

```sh
./components/ktserve/gradlew -p components/ktserve linkStressReleaseExecutableNative
python3 scripts/tests/ktserve_stress.py components/ktserve/build/bin/native/stressReleaseExecutable/ktserve-stress-fixture.kexe
```

On macOS, add `-Pbenchmark.targetOs=macos` to the Gradle command and use the fixture under `components/ktserve/build/macos/bin/native/stressReleaseExecutable/`.

## Upgrade from the preview

Use the `codes.draeger.ktserve` Kotlin package and the `codes.draeger:ktserve` Gradle component. The Ktor factory is `KtServe`. Its artifact is `codes.draeger:ktserve-ktor`. The WebFlux adapter and Spring Boot starter use `codes.draeger:ktserve-webflux` and `codes.draeger:ktserve-spring-boot-starter`. Spring Boot settings begin with `ktserve`, and environment switches use the `KTSERVE_` prefix.

Start a new release archive with `./ktserve` or launch from the built repository with `./scripts/run-server.sh ktserve`. Old snapshot archives retain their original executable names. The dashboard maps historical variant IDs to ktServe while keeping the original archives, logs and CSV rows available.
