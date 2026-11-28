# ktServe extension preview

CORS, Bearer, validation, OpenAPI and access logging are optional modules for standalone HTTP/1.x on JVM and Native.
The OpenTelemetry preview targets the JVM. Use the same version for ktServe and its extensions.

```kotlin
dependencies {
    implementation("codes.draeger:ktserve-cors:<version>")
    implementation("codes.draeger:ktserve-bearer:<version>")
    implementation("codes.draeger:ktserve-openapi:<version>")
    implementation("codes.draeger:ktserve-opentelemetry:<version>")
    implementation("codes.draeger:ktserve-access-log:<version>")
    implementation("codes.draeger:ktserve-validation:<version>")
}
```

## CORS

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.cors.*

fun main() = serve(port = 8080) {
    install(cors {
        allowOrigins("https://app.example")
        allowMethods("GET", "POST")
        allowHeaders("Authorization", "Content-Type")
    })

    get("/health") { text("ok") }
}
```

Configure origins as the exact serialized origins sent by browsers, including a port
if it is part of the origin. Origins and requested headers are opt-in. The default
method set is GET, HEAD and POST. Enable credentialed browser requests with
`allowCredentials = true` and an explicit origin allowlist. For a public resource
without credentials, use `allowAnyOrigin()`.

Install CORS before other extensions that can return an early response. The CORS
extension handles preflights before `beforeEach` and the route handler. Allowed
preflights return 204 and denied preflights return 403. Ordinary OPTIONS requests
continue through the normal routing path. [Fetch CORS protocol](https://fetch.spec.whatwg.org/#http-cors-protocol)

## Bearer

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.bearer.*

fun serveAccounts(verify: suspend (String) -> Boolean) = serve {
    install(bearer())

    getSuspending("/account") { request ->
        val credential = request.bearerCredential
        if (credential is BearerCredential.Present && verify(credential.token)) {
            text("ok")
        } else {
            text("Unauthorized", status = 401)
                .withHeaders(HttpField("WWW-Authenticate", "Bearer"))
        }
    }
}
```

The extension makes a Bearer credential available from the Authorization header.
`Absent` covers missing credentials and other schemes. `Invalid` covers malformed
Bearer credentials and repeated Authorization fields. `Present` contains the opaque
token. Application code verifies the token and chooses the authorization policy and
response. [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750#section-2.1)

## Validation

Install the Konform extension and annotate a typed request body with its rules.
ktServe resolves the rules at startup. Before calling the handler, it returns
HTTP 422 with Problem Details and field pointers for invalid input.

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.validation.*
import kotlinx.serialization.Serializable

@Serializable
@ValidateWith(CreateUser.Rules::class)
data class CreateUser(val name: String) {
    object Rules : KonformRules<CreateUser> by rules({
        minLength(CreateUser::name, 2)
    })
}

fun main() = serve {
    install(validation())

    api("/api") {
        post("/users", status = 201) { input: CreateUser -> input }
    }
}
```

`rules` also accepts ordinary Konform DSL rules, including cross-field constraints.
`minLength(CreateUser::name, 2)` both validates the decoded value and supplies the
same `minLength` to OpenAPI. Pass `jsonName` when the property has a different wire
name. Other Konform rules run at request time. A rules object can contribute
additional schema constraints through `KonformRules.schema`.

Install `validation()` inside an `api` group to limit it to that group and its
children. Pass `jsonBody(validate = alternateRules.asBodyValidation())` when a
route has different rules. [Konform](https://www.konform.io/)

## OpenAPI

Install OpenAPI once to document typed routes from `api { … }`. The document
uses the selected status, media type and serializer. It includes the request
body, registered path parameters and standard request errors. When validation
provides schema constraints, OpenAPI combines them with the input type's JSON
schema. A custom validation function can still report runtime errors.

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.openapi.*
import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Int, val name: String)

fun main() = serve {
    install(openApi(title = "Users", version = "1.0.0") {
        serveAt("/openapi.json")
    })
    api("/v1") {
        get("/health") { "ok" }
        get("/users/me") { User(42, "Ada") }
    }
}
```

Add an `operation` for descriptions, extra statuses or schema overrides.
Use it to describe manually constructed responses and ordinary routes.

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.openapi.*
import kotlinx.serialization.json.*

fun main() = serve {
    val userRoute = "/users/{id}"
    val idSchema = buildJsonObject { put("type", "integer") }
    val userSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { put("id", idSchema) }
        putJsonArray("required") { add("id") }
    }

    install(openApi(title = "Users", version = "1.0.0") {
        serveAt("/openapi.json")
        operation("GET", userRoute) {
            summary = "Get a user by ID"
            pathParameter("id", schema = idSchema)
            response(200, "User", schema = userSchema)
            response(404, "Unknown ID")
        }
    })

    get(userRoute) { request ->
        val id = request.pathInt("id") ?: return@get response(ByteArray(0), status = 404)
        json(mapOf("id" to id))
    }
}
```

ktServe verifies the registered method and path template and generates OpenAPI 3.1 JSON
once at startup. Typed routes default to string path parameters, and manual operations
on ordinary routes require each template parameter and at least one response. Use
`queryParameter`, `requestBody` and `response` to add or override details. Provide a
manual schema when a custom or polymorphic serializer needs a more specific description
than its descriptor supplies.

Use `serveAt` to expose the cached JSON at an explicit GET path. To export the
document instead, use `onDocument { json -> … }` and write it to a destination
appropriate for your application platform. The callback runs during server startup.
The HTTP response counts toward `ServerLimits.responseBodyBytes`.
[OpenAPI 3.1.2](https://spec.openapis.org/oas/v3.1.2.html)

## OpenTelemetry on the JVM

Pass an application-configured `OpenTelemetry` instance to `install(openTelemetry(...))`.
The preview starts a server span when ktServe accepts request headers and ends it after
the response is written or the exchange terminates. The matched route template supplies
`http.route` and the span name once available, including for streaming routes. The span
records the HTTP method, protocol version, selected response status and terminal errors.
[OpenTelemetry HTTP span conventions](https://opentelemetry.io/docs/specs/semconv/http/http-spans/)

Add the Java SDK to the application and select the exporter it should use.

```kotlin
dependencies {
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.68.0"))
    implementation("io.opentelemetry:opentelemetry-sdk")
}
```

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.opentelemetry.*
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler

fun serveTraced(exporter: SpanExporter, stopRequested: () -> Boolean) {
    val provider = SdkTracerProvider.builder()
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(0.1)))
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).setMaxQueueSize(2048).build())
        .build()

    OpenTelemetrySdk.builder()
        .setTracerProvider(provider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
        .use { telemetry ->
            serve(stopRequested = stopRequested) {
                install(openTelemetry(telemetry))
                get("/health") { text("ok") }
            }
        }
}
```

Use a bounded batch processor to export on its worker thread. Close the SDK after
the server drains, as the example does through `use`. The SDK's propagator controls
incoming trace context. The JVM preview traces the HTTP exchange itself. It omits raw
paths, query strings, credentials and payloads from the span attributes.
[OpenTelemetry Java SDK](https://opentelemetry.io/docs/languages/java/sdk/#spanprocessor)

## Access logging

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.accesslog.*

fun main() = serve {
    install(accessLog())
    get("/health") { text("ok") }
}
```

This uses the same logger, batched UTF-8 format and stderr destination as the CLI's
`--access-log`. Each I/O loop records completions in a bounded ring. The background
writer drains the rings after the listener stops and reports any overflow.

Use `accessLog { event -> … }` to format structured events or hand them to your logging
backend. The callback runs on the writer and receives `AccessLogEvent.Exchange` and
`AccessLogEvent.Dropped`. The application selects the facade, backend and output format.
The sink controls its own I/O timeouts. If it throws, the writer stops and the listener continues serving.

For example, a JVM app can forward events through kotlin-logging.

```kotlin
dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.7")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.20")
}
```

```kotlin
import codes.draeger.ktserve.*
import codes.draeger.ktserve.accesslog.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() = serve {
    install(accessLog { event ->
        when (event) {
            is AccessLogEvent.Exchange -> logger.atInfo {
                message = "HTTP exchange"
                payload = mapOf(
                    "method" to event.method,
                    "status" to event.status,
                    "duration_ns" to event.durationNanos,
                    "outcome" to event.outcome.name,
                )
            }
            is AccessLogEvent.Dropped -> logger.warn { "Access log dropped ${event.count} records" }
        }
    })
    get("/health") { text("ok") }
}
```

On the JVM, kotlin-logging delegates to the application's SLF4J provider. Choose a
provider and configure its encoder for formats such as JSON. The default ktServe
configuration writes its regular text batches directly to stderr.
[kotlin-logging](https://github.com/oshai/kotlin-logging) · [SLF4J providers](https://www.slf4j.org/manual.html)

## Static-file packaging

Static-file serving stays in `ktserve` for the preview. The `staticFiles(...)` DSL and
`ktserve serve ./public` both take a bounded snapshot during startup and register file
handlers in the normal router. Apps that do not mount files do not read a directory,
allocate a snapshot or install request hooks. The file loaders use the JVM or platform
filesystem APIs, so this feature adds no external library to the core dependency graph.

An optional module could publish the same `Routes.staticFiles(...)` syntax and use the
same prepared handlers, so packaging alone would not improve request dispatch. It would
need a public way to register literal file routes and lower-priority directory redirects
without shadowing application routes. The extension startup API currently supports literal
GET endpoints with collision checks. Moving the DSL would also require an additional app
dependency, publication for each supported target and bundling the module into the CLI.

Keep cache validation, byte ranges and directory redirects with the file handlers.
If distribution size or a separately maintained file backend justifies moving the feature,
retain route precedence and the existing DSL. TLS, scheduling and HTTP framing stay with
the server and its transports.

## Build and stage

From `components/ktserve`, run the module tests against the local core checkout.

```sh
./gradlew -p extensions jvmTest
```

Run `nativeTest` on a supported host. On macOS, select the macOS target with
`-Pbenchmark.targetOs=macos`. To publish to the local staging repository, set
`-Pktserve.version=<version>` and run `publishAllPublicationsToStagingRepository`
with the platform toolchains for the artifacts being staged. The extension build
writes its artifacts under `extensions/build/maven-staging/<version>`. Stage the
core separately at the same version when testing a Maven consumer.

The [standalone extension consumer](../examples/extensions) resolves the staged
artifacts without a composite build. It installs the official modules alongside a
custom extension that continues requests with a typed attribute or returns an early
response. The Maven staging workflow runs the same HTTP checks on JDK 21, Linux
x86-64, Linux ARM64 and macOS ARM64. Use the workflow's `rehearse_patch` option to
verify the signed artifacts with a temporary key without uploading them to Central.
