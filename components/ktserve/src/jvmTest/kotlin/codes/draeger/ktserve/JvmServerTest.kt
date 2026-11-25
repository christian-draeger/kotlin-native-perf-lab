package codes.draeger.ktserve

import java.io.BufferedInputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmServerTest {
    @Test
    fun responseDeadlineCancelsAStalledProducerAndLeavesOtherConnectionsUsable() {
        val cancelled = CompletableFuture<Unit>()
        val closed = CompletableFuture<Unit>()
        withServer(ServerLimits(responseMillis = 800, idleMillis = 30_000), routes = {
            get("/deadline") {
                streamSuspending(length = 2, onClose = { closed.complete(Unit) }) {
                    try {
                        write("a".encodeToByteArray())
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        }) { port, _ ->
            client(port).use { slow ->
                slow.send("GET /deadline HTTP/1.1\r\nHost: localhost\r\n\r\n")
                val input = slow.getInputStream().buffered()
                assertEquals(2, input.responseLength(200))
                assertEquals('a'.code, input.read())
                client(port).use { other ->
                    other.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    assertEquals("ok", other.getInputStream().buffered().response())
                }
                assertEquals(-1, input.read())
            }
            closed.get(5, TimeUnit.SECONDS)
            cancelled.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun blockingAdmissionRemainsBoundedPastHandlerTimeoutAndShutdown() {
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        try {
            withServer(ServerLimits(blockingHandlers = 1, handlerMillis = 500, drainMillis = 100), routes = {
                getBlocking("/blocking") {
                    entered.complete(Unit)
                    release.get(10, TimeUnit.SECONDS)
                    text("done")
                }
            }) { port, stop ->
                client(port).use { first ->
                    first.send("GET /blocking HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    entered.get(5, TimeUnit.SECONDS)
                    client(port).use { other ->
                        other.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        assertEquals("ok", other.getInputStream().buffered().response())
                    }
                    assertEquals(-1, first.getInputStream().read())
                }
                client(port).use { excess ->
                    excess.send("GET /blocking HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    assertEquals("", excess.getInputStream().buffered().response(status = 503))
                }
                stop.set(true)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun standaloneRoutesDispatchBoundedBodiesAndComposeEarlyPolicies() {
        val authorized = AtomicInteger()
        val updates = AtomicInteger()
        withServer(ServerLimits(requestBodyBytes = 8), routes = {
            beforeEach { request ->
                if (request.path.startsWith("/notes/") && request.header("X-Test-Identity") != "writer") {
                    text("denied", status = 403)
                } else null
            }
            beforeEach { request ->
                if (request.path.startsWith("/notes/")) authorized.incrementAndGet()
                null
            }
            route("PATCH", "/notes/{id}") { request, content ->
                updates.incrementAndGet()
                var size = 0
                while (true) {
                    val chunk = content.readChunk() ?: break
                    size += chunk.size
                }
                text("${request.pathParameter("id")}:$size")
            }
        }) { port, _ ->
            client(port).use { denied ->
                denied.send("PATCH /notes/1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\n")
                val input = denied.getInputStream().buffered()
                assertEquals("denied", input.response(status = 403))
                assertEquals(-1, input.read())
                assertEquals(0, authorized.get())
                assertEquals(0, updates.get())
            }
            client(port).use { allowed ->
                allowed.send(
                    "PATCH /notes/7 HTTP/1.1\r\nHost: localhost\r\nX-Test-Identity: writer\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n" +
                        "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                )
                val input = allowed.getInputStream().buffered()
                assertEquals("7:3", input.response())
                assertEquals("ok", input.response())
                assertEquals(-1, input.read())
            }
            client(port).use { oversized ->
                oversized.send(
                    "PATCH /notes/7 HTTP/1.1\r\nHost: localhost\r\nX-Test-Identity: writer\r\n" +
                        "Content-Length: 9\r\n\r\n",
                )
                val input = oversized.getInputStream().buffered()
                assertEquals("", input.response(status = 413))
                assertEquals(-1, input.read())
                assertEquals(1, updates.get())
            }
        }
    }

    @Test
    fun standaloneHeadAndOptionsUseTheSupportedPathMethods() {
        withServer(ServerLimits(), routes = {
            route("GET", "/resource/{id}") { request, _ -> text("id=${request.pathParameter("id")}") }
            route("PUT", "/resource/{id}") { _, _ -> text("updated") }
            route("HEAD", "/override") { _, _ -> text("custom") }
            get("/override") { text("normal get") }
        }) { port, _ ->
            fun exchange(method: String, path: String): String = client(port).use { socket ->
                socket.send("$method $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                socket.getInputStream().readBytes().decodeToString()
            }
            assertTrue(exchange("HEAD", "/health").let {
                it.startsWith("HTTP/1.1 200 ") && it.contains("Content-Length: 2\r\n") && it.endsWith("\r\n\r\n")
            })
            assertTrue(exchange("HEAD", "/resource/5").let {
                it.startsWith("HTTP/1.1 200 ") && it.contains("Content-Length: 4\r\n") && it.endsWith("\r\n\r\n")
            })
            assertTrue(exchange("HEAD", "/override").contains("Content-Length: 6\r\n"))
            for ((method, status) in listOf("OPTIONS" to 204, "DELETE" to 405)) {
                val reply = exchange(method, "/resource/5")
                assertTrue(reply.startsWith("HTTP/1.1 $status "))
                val allowed = reply.lineSequence().single { it.startsWith("Allow: ") }.removePrefix("Allow: ")
                assertEquals(setOf("GET", "PUT", "HEAD", "OPTIONS"), allowed.split(", ").toSet())
            }
            assertTrue(exchange("OPTIONS", "/missing").startsWith("HTTP/1.1 404 "))
        }
    }

    @Test
    fun suspendingResponseKeepsOrderAndOtherConnectionsProgress() {
        val ready = CompletableDeferred<Unit>()
        withServer(ServerLimits(), routes = {
            get("/slow") {
                streamSuspending(length = 3) {
                    write(byteArrayOf('a'.code.toByte()))
                    ready.await()
                    write("bc".encodeToByteArray())
                }
            }
        }) { port, _ ->
            client(port).use { socket ->
                socket.send("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                    "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                val input = socket.getInputStream().buffered()
                assertEquals(3, input.responseLength(200))
                assertEquals('a'.code, input.read())
                client(port).use { other ->
                    other.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    assertEquals("ok", other.getInputStream().buffered().response())
                }
                ready.complete(Unit)
                assertEquals("bc", input.readNBytes(2).decodeToString())
                assertEquals("ok", input.response())
                assertEquals(-1, input.read())
            }
        }
    }

    @Test
    fun disconnectCancelsAWaitingResponseProducer() {
        val started = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Unit>()
        withServer(ServerLimits(), routes = {
            get("/cancel") {
                streamSuspending(length = 1) {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        }) { port, _ ->
            client(port).use { socket ->
                socket.send("GET /cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
                assertEquals(1, socket.getInputStream().buffered().responseLength(200))
                started.get(5, TimeUnit.SECONDS)
            }
            cancelled.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun streamsApplicationBodiesAcrossBackpressureAndAcceptsNonPostMethods() {
        val reading = CompletableDeferred<Unit>()
        val entered = CompletableFuture<Unit>()
        val body = "x".repeat(24 * 1024)
        withServer(ServerLimits(headerBytes = 4096), routes = {
            application { request, content ->
                when (request.path) {
                    "/health" -> text("ok")
                    "/upload" -> {
                        entered.complete(Unit)
                        reading.await()
                        var bytes = 0
                        while (true) {
                            val chunk = content.readChunk() ?: break
                            check(chunk.all { it == 'x'.code.toByte() })
                            bytes += chunk.size
                        }
                        text("${request.method}:$bytes")
                    }
                    else -> text("missing", status = 404)
                }
            }
        }) { port, _ ->
            client(port).use { socket ->
                socket.send("PUT /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n")
                entered.get(5, TimeUnit.SECONDS)
                socket.send("${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n")
                socket.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                client(port).use { other ->
                    other.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    assertEquals("ok", other.getInputStream().buffered().response())
                }
                reading.complete(Unit)
                val input = socket.getInputStream().buffered()
                assertEquals("PUT:${body.length}", input.response())
                assertEquals("ok", input.response())
                assertEquals(-1, input.read())
            }
        }
    }

    @Test
    fun anEarlyApplicationResponseClosesTheSocketAndHeadOmitsTheBody() {
        withServer(ServerLimits(), routes = {
            application { request, _ ->
                if (request.path == "/health") text("ok") else text("denied", status = 403)
            }
        }) { port, _ ->
            client(port).use { socket ->
                socket.send("PATCH /denied HTTP/1.1\r\nHost: localhost\r\nContent-Length: 64\r\n\r\nx")
                val input = socket.getInputStream().buffered()
                assertEquals("denied", input.response(status = 403))
                assertEquals(-1, input.read())
            }
            client(port).use { socket ->
                socket.send("HEAD /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                val actual = socket.getInputStream().readBytes().decodeToString()
                assertTrue(actual.startsWith("HTTP/1.1 200 "))
                assertTrue(actual.contains("Content-Length: 2\r\n"))
                assertTrue(actual.endsWith("\r\n\r\n"))
            }
        }
    }

    @Test
    fun resumesSuspendedHandlerAndPreservesRequestOrder() {
        val entered = CompletableFuture<Unit>()
        val ready = CompletableDeferred<Unit>()
        withServer(ServerLimits(), routes = {
            getSuspending("/async/{id}") { request ->
                entered.complete(Unit)
                ready.await()
                text(checkNotNull(request.pathParameter("id")))
            }
        }) { port, _ ->
            client(port).use { first ->
                first.send("GET /async/123 HTTP/1.1\r\nHost: localhost\r\n\r\n")
                entered.get(5, TimeUnit.SECONDS)
                first.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                client(port).use { second ->
                    second.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    assertEquals("ok", second.getInputStream().buffered().response())
                }
                ready.complete(Unit)
                val input = first.getInputStream().buffered()
                assertEquals("123", input.response())
                assertEquals("ok", input.response())
                assertEquals(-1, input.read())
            }
        }
    }

    @Test
    fun cancelsOnDisconnectAndDeadlineAndReleasesLateResponse() {
        val entered = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Unit>()
        val sourceEntered = CompletableFuture<Unit>()
        val sourceClosed = CompletableFuture<Unit>()
        val ready = CompletableFuture<Unit>()
        withServer(ServerLimits(handlerMillis = 1_000), routes = {
            getSuspending("/disconnect") {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            getSuspending("/source") {
                suspendCoroutine<Unit> { resume ->
                    ready.thenRun { resume.resume(Unit) }
                    sourceEntered.complete(Unit)
                }
                streamResponse(1, "text/plain", object : ResponseSource {
                    override fun read(buffer: ByteArray, limit: Int): Int = error("source was never written")
                    override fun close() {
                        sourceClosed.complete(Unit)
                    }
                })
            }
        }) { port, _ ->
            client(port).use { socket ->
                socket.send("GET /disconnect HTTP/1.1\r\nHost: localhost\r\n\r\n")
                entered.get(5, TimeUnit.SECONDS)
            }
            cancelled.get(5, TimeUnit.SECONDS)
            client(port).use { socket ->
                socket.send("GET /source HTTP/1.1\r\nHost: localhost\r\n\r\n")
                try {
                    sourceEntered.get(5, TimeUnit.SECONDS)
                    assertEquals(-1, socket.getInputStream().read())
                } finally {
                    ready.complete(Unit)
                }
                sourceClosed.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun streamsAResponseAndProcessesThePipelinedRequest() {
        val closed = AtomicInteger()
        val bodyLength = 128 * 1024 + 3
        withServer(ServerLimits(responseBodyBytes = bodyLength), routes = {
            get("/stream") {
                streamResponse(bodyLength, "text/plain", object : ResponseSource {
                    override fun read(buffer: ByteArray, limit: Int): Int {
                        buffer.fill('x'.code.toByte(), toIndex = limit)
                        return limit
                    }

                    override fun close() {
                        closed.incrementAndGet()
                    }
                })
            }
        }) { port, _ ->
            client(port).use { socket ->
                val input = socket.getInputStream().buffered()
                socket.send("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r")
                socket.send("\nGET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                assertEquals("x".repeat(bodyLength), input.response())
                assertEquals("ok", input.response())
                assertEquals(-1, input.read())
                assertEquals(1, closed.get())
            }
        }
    }

    @Test
    fun enforcesConnectionCapAndDeadlinesAndReleasesTheListener() {
        val stoppedPort = withServer(ServerLimits(connections = 1, requestHeadMillis = 400, idleMillis = 2_000)) { port, stop ->
            assertFailsWith<BindException> { serve(port = port) {} }
            client(port).use { active ->
                active.send("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
                val input = active.getInputStream().buffered()
                assertEquals("ok", input.response())
                client(port).use { overflow -> assertEquals(-1, overflow.getInputStream().read()) }
                active.send("GET /health")
                assertEquals(-1, input.read())
            }
            client(port).use { idle ->
                idle.send("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
                val input = idle.getInputStream().buffered()
                assertEquals("ok", input.response())
                stop.set(true)
                assertEquals(-1, input.read())
            }
            port
        }
        serve(port = stoppedPort, stopRequested = { true }) {}
    }

    private fun <T> withServer(
        limits: ServerLimits,
        routes: Routes.() -> Unit = {},
        test: (Int, AtomicBoolean) -> T,
    ): T {
        val port = ServerSocket(0).use { it.localPort }
        val stop = AtomicBoolean()
        val finished = CompletableFuture<Unit>()
        val worker = thread(name = "ktserve-test-loop", isDaemon = true) {
            try {
                serve(port = port, limits = limits, stopRequested = stop::get) {
                    routes()
                    if (applicationHandler == null) get("/health") { text("ok") }
                }
                finished.complete(Unit)
            } catch (cause: Throwable) {
                finished.completeExceptionally(cause)
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                if (finished.isDone) finished.get()
                try {
                    client(port).use { probe ->
                        probe.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        val input = probe.getInputStream().buffered()
                        assertEquals("ok", input.response())
                        assertEquals(-1, input.read())
                    }
                    break
                } catch (_: java.net.ConnectException) {
                    check(System.nanoTime() < deadline) { "JVM ktServe did not bind its test listener" }
                    Thread.sleep(10)
                }
            }
            return test(port, stop)
        } finally {
            stop.set(true)
            finished.get(5, TimeUnit.SECONDS)
            worker.join()
        }
    }

    private fun client(port: Int): Socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }

    private fun Socket.send(value: String) {
        getOutputStream().write(value.encodeToByteArray())
    }

    private fun BufferedInputStream.response(status: Int = 200): String {
        val size = responseLength(status)
        val body = readNBytes(size)
        assertEquals(size, body.size)
        return body.decodeToString()
    }

    private fun BufferedInputStream.responseLength(status: Int): Int {
        val reader = this
        fun line(): String = buildString {
            while (true) {
                val char = reader.read()
                check(char >= 0) { "Connection closed inside response head" }
                if (char == '\n'.code) break
                append(char.toChar())
            }
        }.removeSuffix("\r")

        assertTrue(line().startsWith("HTTP/1.1 $status "))
        var length: Int? = null
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length: ")) length = header.substringAfter(": ").toInt()
        }
        return checkNotNull(length)
    }
}
