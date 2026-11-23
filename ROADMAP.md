# Kotlin Native Performance Lab roadmap

Build NativeServe, an HTTP framework for Kotlin/Native with a short, idiomatic setup path and a reproducible comparison against Rust/Axum. GitHub issues hold the acceptance criteria and prerequisites for each change. [Milestones](https://github.com/christian-draeger/kotlin-native-perf-lab/milestones) · [Issues](https://github.com/christian-draeger/kotlin-native-perf-lab/issues)

## Developer experience

The first complete example should look like a small application. A caller sets a port, registers handlers, and returns text or a serializable Kotlin value. Framework users work with Kotlin types and get the same setup on Linux x86_64 and ARM64.

```kotlin
fun main() {
    serve(port = 8080) {
        get("/health") { text("ok") }
        get("/users/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            json(User(id, "benchmark-user", true))
        }
    }
}
```

The first public preview should provide a runnable starter, one documented build/run command, a Gradle dependency example, and a curl check. Document binding, request and response limits, timeouts, how failures surface, and how to stop the process. Keep operational defaults useful for local development and expose configuration through Kotlin types and ordinary environment or command-line options where the sample needs them.

## I/O reactor design

- Keep the new library and executable in `kotlin-reactor/`. The benchmark application calls the same public routing and response APIs as a consumer.
- Start with one level-triggered epoll loop. Use nonblocking sockets, retain bytes across partial input and output, and register write interest while there are bytes left to send. The first reactor uses one owner per connection.
- Parse a bounded request head incrementally. Keep HTTP framing validation, route selection, JSON encoding and I/O as separate boundaries. Start with origin-form HTTP/1.x GET requests. Cover the parity endpoints and document the supported request-body and method scope.
- Cap concurrent sockets, request head size, retained pending output and connection idle time. Close a connection when parsing or I/O can no longer progress safely. Consumers get useful messages on startup failures.
- Keep file descriptor creation, registration and closure in one place. The reactor owns the listener, epoll descriptor and per-connection state. The connection owns its request bytes and pending response. Keep native event storage scoped to the event loop and pin Kotlin buffers for individual syscalls.
- Keep the parser and routing core runnable as portable unit tests. Run actual native HTTP smoke on Linux x86_64 and ARM64. The ARM64 build is cross-compiled on supported x86_64 hosts and executed on ARM64.

## Milestones and order of work

| Milestone | Deliverable | Exit evidence |
| --- | --- | --- |
| [0 · Baseline and validation](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/1) | Merge the accepted phase-0 matrix and separate quick checks from native and benchmark runs. | Baseline smoke, Pages build, preserved raw data, and measured time to actionable feedback. |
| [1 · NativeServe reference server](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/2) | Implement the bounded parser and epoll loop, expose the parity routes, and integrate a fourth benchmark variant. | Unit and HTTP checks on both architectures, one archived four-variant CI series per architecture. |
| [2 · Framework developer experience](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/3) | Refine routing and typed JSON, verify the starter, and make shutdown and startup failures predictable. | A fresh-checkout example using the public API succeeds with the documented commands. |
| [3 · Controlled measurement and profiles](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/4) | Run a native Linux reference host with a separate load generator, validate steady state and collect symbolized profiles. | Matched release workloads, raw trials on each tested architecture, measured dispersion and at least one ranked bottleneck. |
| [4 · Measured optimization](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/5) | Evaluate scheduling, buffers, generated route matching and serialization one at a time when the profiles warrant them. | Before and after trials for all four variants and a recorded decision for each change, including regressions. |
| [5 · Supported preview release](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/6) | Harden the supported HTTP surface, package both Linux architectures and publish onboarding with the measured scorecard. | Verified binaries and consumer example, checksums, compatibility notes, resource bounds and a public result with its conditions. |
| [6 · Ktor Native engine and comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/7) | Extend the transport for streaming and cancellation, then provide an engine selected through normal Ktor configuration. | The same Ktor Native application passes compatibility checks and a controlled CIO versus NativeServe comparison on the documented platforms. |
| [7 · JVM transport and Ktor comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/8) | Reuse the shared protocol and routing logic with a JVM transport and a packaged Ktor JVM engine. | A clean-checkout consumer and a measured comparison of the same Ktor JVM application on CIO, Netty and NativeServe. |
| [8 · Spring WebFlux integration and comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/milestone/9) | Implement the reactive HTTP adapter and Boot starter, compare engines, then assess Servlet compatibility. | Backpressure and lifecycle tests, a one-dependency consumer, a controlled Reactor Netty versus NativeServe comparison and a documented Spring MVC feasibility decision. |

The critical path starts with [baseline integration](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/2), [quick CI feedback](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/3), [resource ownership](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/4), then [parsing](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/5), [transport](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/6), [parity](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/7) and [four-way measurement](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/8). The controlled host work can proceed alongside API, docs and packaging preparation.

## Framework integration sequence

After the [supported preview](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/24), the integration work proceeds through three measured stages.

| Stage | Implementation and validation issues |
| --- | --- |
| Ktor Native | [Bounded streaming and cancellation](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/37), [engine adapter](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/38), then [CIO comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/39). |
| JVM | [Shared-API transport](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/40), [Ktor engine and consumer package](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/41), then [CIO and Netty comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/42). |
| Spring WebFlux | [Reactive HTTP adapter](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/43), [Boot starter](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/44), then [Reactor Netty comparison](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/45). |

Each engine comparison uses the same application module, routes, plugins or filters, codecs and response semantics across its variants. Add equivalent request-body, streaming and asynchronous-handler workloads in dedicated integration fixtures. Record the supported feature surface and verify cancellation, resource limits and lifecycle before collecting load measurements.

Give each integration suite its own recorded identity, including framework, engine, runtime, architecture and exact launch configuration, and publish its raw evidence and history on Pages. Use dedicated hosts, validated warm-up and at least five valid repetitions per cell for controlled conclusions. Record regressions and choose a measured tolerance for each integration.

After the WebFlux comparison, [assess Spring MVC and Servlet compatibility](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/46). The assessment covers Servlet contracts, maintenance cost, operational behavior and the evidence for investing in a full implementation. A decision to proceed creates a dedicated delivery milestone with a matched Tomcat or Jetty comparison.

## Validation and performance decisions

| Layer | When to run | Signal |
| --- | --- | --- |
| Local quick checks | On each edit before pushing | Shell syntax, dashboard tests, static site build and portable reactor tests as they become available. |
| Native smoke | On pull requests and main | Release binaries run on each architecture, with status, bytes, headers and keep-alive checked. x86_64 reports independently of ARM64 cross-compilation. |
| Hosted benchmark | On demand for an implementation or configuration change | Four-way directional measurements and raw data on Pages, separated per architecture and marked exploratory. |
| Controlled benchmark | Before drawing conclusions or setting a release performance gate | Matched release binaries on a dedicated native test host and an independent load machine. Record warm-up, errors, bin hashes, p50/p95/p99, CPU seconds per million requests, RSS and trial dispersion. |

Pick the workload before measuring the ratio to Axum. A working target to test is at least 90% of Axum throughput with p99 and CPU cost within 10% at comparable successful load. Report RSS and connection concurrency alongside those results. Assess confidence across runs, endpoints and architectures before using the scorecard in release documentation. Keep the metrics and raw evidence when a change is slower.

Keep re-checking *time to the first actionable result* as the validation suite grows. Use exact CI job links and measured job timestamps in validation issues. A completed issue links to the implementation and checks that satisfy its acceptance criteria. Keep conditions that need new hardware or workload evidence open in GitHub.
