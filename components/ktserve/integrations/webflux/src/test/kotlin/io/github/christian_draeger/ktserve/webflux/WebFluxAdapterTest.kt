package io.github.christian_draeger.ktserve.webflux

import io.github.christian_draeger.ktserve.ServerLimits
import io.netty.buffer.PooledByteBufAllocator
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer

class WebFluxAdapterTest {
    @Test
    fun theSameWebFluxApplicationRunsOnKtServeAndReactorNetty() {
        val handler = RouterFunctions.toHttpHandler(router {
            GET("/health") { ServerResponse.ok().header("X-Engine-Test", "shared").bodyValue("ok") }
            GET("/json") {
                ServerResponse.ok().bodyValue(linkedMapOf("id" to 42, "name" to "benchmark-user", "active" to true))
            }
            POST("/upload") { request ->
                request.bodyToFlux(DataBuffer::class.java)
                    .map {
                        try {
                            it.readableByteCount()
                        } finally {
                            DataBufferUtils.release(it)
                        }
                    }
                    .reduce(0, Int::plus)
                    .flatMap { ServerResponse.ok().bodyValue(it.toString()) }
            }
            GET("/download") {
                val factory = DefaultDataBufferFactory.sharedInstance
                val first = Mono.just(factory.wrap(ByteArray(16 * 1024) { 's'.code.toByte() }))
                val last = Mono.delay(Duration.ofMillis(40))
                    .map { factory.wrap(ByteArray(16 * 1024 + 3) { 't'.code.toByte() }) }
                ServerResponse.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(Flux.concat(first, last), DataBuffer::class.java)
            }
        })

        withKtServe(handler, ::checkApplication)
        val reactor = HttpServer.create().host("127.0.0.1").port(0)
            .handle(ReactorHttpHandlerAdapter(handler)).bindNow()
        try {
            checkApplication(reactor.port())
        } finally {
            reactor.disposeNow()
        }
    }

    @Test
    fun releasesPooledOutputAndCancelsThePublisherOnDisconnect() {
        val first = PooledByteBufAllocator.DEFAULT.directBuffer(1).writeByte('x'.code)
        val buffers = NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT)
        val cancelled = CompletableFuture<Unit>()
        val handler = HttpHandler { _, response ->
            val body = Flux.concat(Flux.just<DataBuffer>(buffers.wrap(first)), Flux.never())
                .doOnCancel { cancelled.complete(Unit) }
            response.writeWith(body)
        }
        withKtServe(handler) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                val input = socket.getInputStream().bufferedReader()
                assertTrue(input.readLine().startsWith("HTTP/1.1 200 "))
                while (input.readLine().isNotEmpty()) {
                    // Read the complete response head before checking the first chunk.
                }
                assertEquals("1", input.readLine())
                assertEquals('x'.code, input.read())
                assertEquals(0, first.refCnt())
            }
            cancelled.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun aSlowClientBackpressuresThePublisherAndAnEarlyFailureReturnsHttp500() {
        val chunk = ByteArray(16 * 1024)
        val maxBytes = 16 * 1024 * 1024
        val emitted = AtomicInteger()
        val cancelled = CompletableFuture<Unit>()
        val handler = HttpHandler { request, response ->
            val output = when (request.uri.path) {
                "/failure" -> Flux.error(IllegalStateException("fixture publisher failure"))
                else -> Flux.range(0, maxBytes / chunk.size).map<DataBuffer> {
                    emitted.incrementAndGet()
                    DefaultDataBufferFactory.sharedInstance.wrap(chunk)
                }.doOnCancel { cancelled.complete(Unit) }
            }
            response.writeWith(output)
        }
        withKtServe(handler, ServerLimits(responseBodyBytes = maxBytes)) { port ->
            val failure = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/failure")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(500, failure.statusCode())
            Socket().use { socket ->
                socket.receiveBufferSize = 4096
                socket.soTimeout = 5000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port))
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                assertEquals('H'.code, socket.getInputStream().read())
                Thread.sleep(100)
                assertTrue(emitted.get() in 1 until maxBytes / chunk.size)
            }
            cancelled.get(5, TimeUnit.SECONDS)
        }
    }

    private fun checkApplication(port: Int) {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        fun request(path: String, body: ByteArray? = null): HttpResponse<ByteArray> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
            if (body != null) builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }
        val health = request("/health")
        assertEquals(200, health.statusCode())
        assertEquals("ok", health.body().decodeToString())
        assertEquals("shared", health.headers().firstValue("X-Engine-Test").get())
        assertEquals("""{"id":42,"name":"benchmark-user","active":true}""", request("/json").body().decodeToString())
        assertEquals("24576", request("/upload", ByteArray(24 * 1024)).body().decodeToString())
        assertContentEquals(
            ByteArray(16 * 1024) { 's'.code.toByte() } + ByteArray(16 * 1024 + 3) { 't'.code.toByte() },
            request("/download").body(),
        )
        assertEquals(404, request("/missing").statusCode())
    }

    private fun withKtServe(handler: HttpHandler, test: (Int) -> Unit) =
        withKtServe(handler, ServerLimits(), test)

    private fun withKtServe(handler: HttpHandler, limits: ServerLimits, test: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val ready = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val stop = AtomicBoolean()
        val worker = thread(isDaemon = true, name = "ktserve-webflux-test") {
            try {
                serveWebFlux(handler, port, limits = limits, stopRequested = stop::get, onListening = { ready.complete(Unit) })
                finished.complete(Unit)
            } catch (cause: Throwable) {
                ready.completeExceptionally(cause)
                finished.completeExceptionally(cause)
            }
        }
        try {
            ready.get(5, TimeUnit.SECONDS)
            test(port)
        } finally {
            stop.set(true)
            finished.get(5, TimeUnit.SECONDS)
            worker.join()
        }
    }
}
