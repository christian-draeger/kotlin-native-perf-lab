package dev.perflab.nativeserve

import java.io.BufferedInputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmServerTest {
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
        val worker = thread(name = "nativeserve-test-loop", isDaemon = true) {
            try {
                serve(port = port, limits = limits, stopRequested = stop::get) {
                    get("/health") { text("ok") }
                    routes()
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
                    check(System.nanoTime() < deadline) { "JVM NativeServe did not bind its test listener" }
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

    private fun BufferedInputStream.response(): String {
        val reader = this
        fun line(): String = buildString {
            while (true) {
                val char = reader.read()
                check(char >= 0) { "Connection closed inside response head" }
                if (char == '\n'.code) break
                append(char.toChar())
            }
        }.removeSuffix("\r")

        assertTrue(line().startsWith("HTTP/1.1 200 "))
        var length: Int? = null
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length: ")) length = header.substringAfter(": ").toInt()
        }
        val size = checkNotNull(length)
        val body = readNBytes(size)
        assertEquals(size, body.size)
        return body.decodeToString()
    }
}
