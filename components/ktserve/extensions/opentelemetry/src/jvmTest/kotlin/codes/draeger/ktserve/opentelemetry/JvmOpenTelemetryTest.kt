package codes.draeger.ktserve.opentelemetry

import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.streamSuspending
import codes.draeger.ktserve.text
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation

class JvmOpenTelemetryTest {
    @Test
    fun propagatesW3cContextAndRecordsTheMatchedTemplateWithoutRequestData() {
        val exporter = InMemorySpanExporter.create()
        sdk(SimpleSpanProcessor.create(exporter)).use { telemetry ->
            withServer({
                install(openTelemetry(telemetry))
                get("/items/{id}") { text("private-response-body") }
            }) { port ->
                assertEquals(
                    200,
                    request(
                        port,
                        "/items/987?filter=private-query",
                        "Traceparent: $traceparent",
                        "Tracestate: vendor=first",
                        "Tracestate: partner=second",
                        "Authorization: Bearer private-credential",
                    ),
                )
            }

            val span = exporter.finishedSpanItems.single()
            assertEquals(SpanKind.SERVER, span.kind)
            assertEquals("GET /items/{id}", span.name)
            assertEquals(traceId, span.traceId)
            assertEquals(parentId, span.parentSpanId)
            assertTrue(span.parentSpanContext.isRemote)
            assertEquals("first", span.parentSpanContext.traceState.get("vendor"))
            assertEquals("second", span.parentSpanContext.traceState.get("partner"))
            assertEquals("GET", span.attributes.get(method))
            assertEquals("1.1", span.attributes.get(protocolVersion))
            assertEquals("/items/{id}", span.attributes.get(route))
            assertEquals(200L, span.attributes.get(statusCode))
            assertEquals(StatusCode.UNSET, span.status.statusCode)
            assertNull(span.attributes.get(errorType))
            assertTrue(span.endEpochNanos >= span.startEpochNanos)
            val attributes = span.attributes.asMap()
            assertFalse(attributes.keys.any { it.key.startsWith("http.request.header.") || it.key in rawUrlAttributes })
            assertFalse(attributes.values.any { "/items/987" in it.toString() || "private-" in it.toString() })
        }
    }

    @Test
    fun observesEarlyRejectionsAndDistinguishesHttpErrorsFromUnmatchedRequests() {
        val exporter = InMemorySpanExporter.create()
        sdk(SimpleSpanProcessor.create(exporter)).use { telemetry ->
            withServer({
                install(extension("example.early-guard") {
                    onRequest { exchange ->
                        if (exchange.request.path == "/blocked") RequestDecision.Respond(text("denied", status = 403))
                        else RequestDecision.Continue
                    }
                })
                install(openTelemetry(telemetry))
                get("/blocked") { error("The early guard should answer first") }
                get("/broken") { error("private-handler-error") }
            }) { port ->
                assertEquals(403, request(port, "/blocked", "Traceparent: $traceparent"))
                assertEquals(500, request(port, "/broken", "Traceparent: malformed"))
                assertEquals(
                    404,
                    request(port, "/missing/private-path", "Traceparent: $traceparent", "traceparent: $traceparent"),
                )
            }

            val spans = exporter.finishedSpanItems
            assertEquals(3, spans.size)
            val byStatus = spans.associateBy { it.attributes.get(statusCode) }
            val blocked = byStatus.getValue(403L)
            assertEquals(parentId, blocked.parentSpanId)
            assertEquals(StatusCode.UNSET, blocked.status.statusCode)
            assertEquals("GET", blocked.name)
            assertNull(blocked.attributes.get(route))
            assertNull(blocked.attributes.get(errorType))

            val broken = byStatus.getValue(500L)
            assertFalse(broken.parentSpanContext.isValid, "Malformed traceparent must start a new trace")
            assertEquals("GET /broken", broken.name)
            assertEquals(StatusCode.ERROR, broken.status.statusCode)
            assertEquals("500", broken.attributes.get(errorType))

            val missing = byStatus.getValue(404L)
            assertFalse(missing.parentSpanContext.isValid, "Duplicate traceparent must start a new trace")
            assertEquals("GET", missing.name)
            assertNull(missing.attributes.get(route))
            assertNull(missing.attributes.get(errorType))
            assertEquals(StatusCode.UNSET, missing.status.statusCode)
        }
    }

    @Test
    fun exportsAnInterruptedStreamOnceAfterThePeerDisconnects() {
        val exporter = InMemorySpanExporter.create()
        val cancelled = CompletableFuture<Unit>()
        sdk(SimpleSpanProcessor.create(exporter)).use { telemetry ->
            withServer({
                install(openTelemetry(telemetry))
                get("/stream") {
                    streamSuspending(length = 2) {
                        try {
                            write("a".encodeToByteArray())
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                }
            }) { port ->
                client(port).use { socket ->
                    socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                    val input = socket.getInputStream().buffered()
                    assertTrue(input.line().startsWith("HTTP/1.1 200 "))
                    while (input.line().isNotEmpty()) {
                        // Skip response header lines before reading the first body byte.
                    }
                    assertEquals('a'.code, input.read())
                    assertTrue(exporter.finishedSpanItems.isEmpty(), "The span must stay open during the stream")
                }
                cancelled.get(5, TimeUnit.SECONDS)
            }

            val span = exporter.finishedSpanItems.single()
            assertEquals("GET /stream", span.name)
            assertEquals(StatusCode.ERROR, span.status.statusCode)
            assertEquals("disconnected", span.attributes.get(errorType))
        }
    }

    @Test
    fun endsTheSpanWhenAHandlerTimesOutWithoutSelectingAResponse() {
        val exporter = InMemorySpanExporter.create()
        val entered = CompletableFuture<Unit>()
        sdk(SimpleSpanProcessor.create(exporter)).use { telemetry ->
            withServer({
                install(openTelemetry(telemetry))
                getSuspending("/wait") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }, limits = ServerLimits(handlerMillis = 500)) { port ->
                client(port).use { socket ->
                    socket.getOutputStream().write(waitRequest)
                    entered.get(5, TimeUnit.SECONDS)
                    assertEquals(-1, socket.getInputStream().read())
                }
            }

            val span = exporter.finishedSpanItems.single()
            assertEquals("GET /wait", span.name)
            assertEquals(StatusCode.ERROR, span.status.statusCode)
            assertEquals("handler_timeout", span.attributes.get(errorType))
            assertNull(span.attributes.get(statusCode))
        }
    }

    @Test
    fun endsAStillPendingSpanWhenTheServerDrains() {
        val exporter = InMemorySpanExporter.create()
        val entered = CompletableFuture<Unit>()
        var waitingSocket: Socket? = null
        sdk(SimpleSpanProcessor.create(exporter)).use { telemetry ->
            try {
                withServer({
                    install(openTelemetry(telemetry))
                    getSuspending("/wait") {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }, limits = ServerLimits(drainMillis = 50)) { port ->
                    val socket = client(port).also { waitingSocket = it }
                    socket.getOutputStream().write(waitRequest)
                    entered.get(5, TimeUnit.SECONDS)
                    assertTrue(exporter.finishedSpanItems.isEmpty())
                }

                val span = exporter.finishedSpanItems.single()
                assertEquals("GET /wait", span.name)
                assertEquals(StatusCode.ERROR, span.status.statusCode)
                assertEquals("shutdown", span.attributes.get(errorType))
                assertNull(span.attributes.get(statusCode))
            } finally {
                waitingSocket?.close()
            }
        }
    }

    @Test
    fun boundedBatchExportLeavesTheIoLoopFreeToHandleAnotherRequest() {
        val memory = InMemorySpanExporter.create()
        val reactor = CompletableFuture<Thread>()
        val exporting = CompletableFuture<Thread>()
        val releaseExporter = CountDownLatch(1)
        val exporter = object : SpanExporter by memory {
            override fun export(spans: Collection<SpanData>): CompletableResultCode {
                exporting.complete(Thread.currentThread())
                if (!releaseExporter.await(10, TimeUnit.SECONDS)) return CompletableResultCode.ofFailure()
                return memory.export(spans)
            }
        }
        val processor = BatchSpanProcessor.builder(exporter)
            .setMaxQueueSize(8)
            .setMaxExportBatchSize(1)
            .build()
        sdk(processor).use { telemetry ->
            try {
                withServer({
                    install(openTelemetry(telemetry))
                    get("/health") {
                        reactor.complete(Thread.currentThread())
                        text("ok")
                    }
                }) { port ->
                    assertEquals(200, request(port, "/health"))
                    assertNotSame(reactor.get(5, TimeUnit.SECONDS), exporting.get(5, TimeUnit.SECONDS))
                    assertEquals(200, request(port, "/health"))
                }
            } finally {
                releaseExporter.countDown()
            }
            assertTrue(telemetry.sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess)
            assertEquals(listOf("GET /health", "GET /health"), memory.finishedSpanItems.map { it.name })
        }
    }

    private fun sdk(processor: SpanProcessor): OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(processor).build())
        .build()

    private fun withServer(
        configure: Routes.() -> Unit,
        limits: ServerLimits = ServerLimits(),
        test: (Int) -> Unit,
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val stopped = AtomicBoolean()
        val listening = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val loop = thread(name = "ktserve-opentelemetry-test-loop", isDaemon = true) {
            try {
                serve(port = port, limits = limits, stopRequested = stopped::get, onListening = { listening.complete(Unit) }, configure = configure)
                finished.complete(Unit)
            } catch (failure: Throwable) {
                listening.completeExceptionally(failure)
                finished.completeExceptionally(failure)
            }
        }
        try {
            listening.get(5, TimeUnit.SECONDS)
            test(port)
        } finally {
            stopped.set(true)
            finished.get(5, TimeUnit.SECONDS)
            loop.join()
        }
    }

    private fun request(port: Int, path: String, vararg headers: String): Int = client(port).use { socket ->
        val request = buildString {
            append("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
            for (header in headers) append(header).append("\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(request.encodeToByteArray())
        val input = socket.getInputStream().buffered()
        val status = input.line()
        assertTrue(status.startsWith("HTTP/1.1 "))
        input.readAllBytes()
        status.split(' ', limit = 3)[1].toInt()
    }

    private fun client(port: Int): Socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }

    private fun BufferedInputStream.line(): String = buildString {
        while (true) {
            val byte = read()
            check(byte >= 0) { "Connection closed inside response head" }
            if (byte == '\n'.code) break
            append(byte.toChar())
        }
    }.removeSuffix("\r")

    private companion object {
        const val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val parentId = "00f067aa0ba902b7"
        const val traceparent = "00-$traceId-$parentId-01"
        val method = AttributeKey.stringKey("http.request.method")
        val protocolVersion = AttributeKey.stringKey("network.protocol.version")
        val route = AttributeKey.stringKey("http.route")
        val statusCode = AttributeKey.longKey("http.response.status_code")
        val errorType = AttributeKey.stringKey("error.type")
        val rawUrlAttributes = setOf("url.full", "url.path", "url.query")
        val waitRequest = "GET /wait HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray()
    }
}
