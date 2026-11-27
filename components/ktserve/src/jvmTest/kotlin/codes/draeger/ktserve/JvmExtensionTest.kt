package codes.draeger.ktserve

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmExtensionTest {
    @Test
    fun installedExtensionsRunInOrderAndCarryStateAcrossSuspendingRouteDispatch() {
        val identity = RequestAttribute<String>("example.identity")
        val template = RequestAttribute<String>("example.template")
        val entered = CompletableFuture<Unit>()
        val resume = CompletableDeferred<Unit>()
        val requests = ConcurrentLinkedQueue<String>()
        val readIdentity = extension("example.read-identity") {
            onRequest { exchange ->
                requests.add("identity:${exchange.request.path}")
                exchange.request.header("X-Identity")?.let { exchange[identity] = it }
                RequestDecision.Continue
            }
            onRoute { exchange, route ->
                exchange[template] = "${route.method} ${route.template}"
                RequestDecision.Continue
            }
            onResponseHeaders { _, headers ->
                headers.set("X-Order", "first")
                headers.set("X-Replaced", "first")
            }
        }
        val addHeaders = extension("example.add-headers") {
            onRequest { exchange ->
                requests.add("headers:${exchange.request.path}:${exchange[identity]}")
                RequestDecision.Continue
            }
            onResponseHeaders { exchange, headers ->
                headers.append("X-Order", "second")
                headers.set("X-Replaced", "second")
                exchange[template]?.let { headers.set("X-Route", it) }
            }
        }

        withServer(routes = {
            install(readIdentity)
            install(addHeaders)
            getSuspending("/items/{id}") { request ->
                entered.complete(Unit)
                resume.await()
                text("${request.pathParameter("id")}:${request.attribute(identity)}")
            }
            get("/health") { request -> text(request.attribute(identity) ?: "missing") }
        }) { port ->
            client(port).use { socket ->
                socket.send("GET /items/7 HTTP/1.1\r\nHost: localhost\r\nX-Identity: owner\r\n\r\n")
                entered.get(5, TimeUnit.SECONDS)
                socket.send("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                resume.complete(Unit)

                val input = socket.getInputStream().buffered()
                val item = input.response(200)
                assertEquals("7:owner", item.body)
                assertEquals(listOf("GET /items/{id}"), item.headers("X-Route"))
                assertEquals(listOf("first", "second"), item.headers("X-Order"))
                assertEquals(listOf("second"), item.headers("X-Replaced"))
                val health = input.response(200)
                assertEquals("missing", health.body)
                assertEquals(listOf("GET /health"), health.headers("X-Route"))
                assertEquals(-1, input.read())
            }
        }
        assertEquals(
            listOf("identity:/items/7", "headers:/items/7:owner", "identity:/health", "headers:/health:null"),
            requests.toList(),
        )
    }

    @Test
    fun earlyAndErrorResponsesStillRunResponseAndCompletionHooks() {
        val forwarded = ConcurrentLinkedQueue<String>()
        val completed = ConcurrentLinkedQueue<Triple<String, Int?, ExchangeOutcome>>()
        withServer(routes = {
            install(extension("example.early") {
                onRequest { exchange ->
                    if (exchange.request.path == "/protected") RequestDecision.Respond(text("denied", status = 403))
                    else RequestDecision.Continue
                }
            })
            install(extension("example.audit") {
                onRequest { exchange ->
                    forwarded.add(exchange.request.path)
                    RequestDecision.Continue
                }
                onResponseHeaders { _, headers -> headers.set("X-Extension", "applied") }
                onComplete { exchange, result ->
                    completed.add(Triple(exchange.request.path, result.status, result.outcome))
                }
            })
            route("PATCH", "/protected") { _, _ -> error("An early response must skip the handler") }
            get("/error") { error("Expected application error") }
        }) { port ->
            client(port).use { socket ->
                socket.send("PATCH /protected HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\n")
                val input = socket.getInputStream().buffered()
                val denied = input.response(403)
                assertEquals("denied", denied.body)
                assertEquals(listOf("applied"), denied.headers("X-Extension"))
                assertEquals(-1, input.read())
            }
            client(port).use { socket ->
                socket.send("GET /error HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                val input = socket.getInputStream().buffered()
                assertEquals(listOf("applied"), input.response(500).headers("X-Extension"))
                assertEquals(-1, input.read())
            }
        }
        assertEquals(listOf("/error"), forwarded.toList())
        assertEquals(
            listOf(Triple("/protected", 403, ExchangeOutcome.COMPLETED), Triple("/error", 500, ExchangeOutcome.COMPLETED)),
            completed.toList(),
        )
    }

    @Test
    fun disconnectDuringStreamingCompletesTheExchangeOnce() {
        val cancelled = CompletableFuture<Unit>()
        val completion = CompletableFuture<ExchangeOutcome>()
        val outcomes = ConcurrentLinkedQueue<ExchangeOutcome>()
        withServer(routes = {
            install(extension("example.stream") {
                onResponseHeaders { _, headers -> headers.set("X-Extension", "applied") }
                onComplete { _, result ->
                    outcomes.add(result.outcome)
                    completion.complete(result.outcome)
                }
            })
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
                socket.send("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n")
                val input = socket.getInputStream().buffered()
                assertTrue(("x-extension" to "applied") in input.responseHead(200))
                assertEquals('a'.code, input.read())
            }
            assertEquals(ExchangeOutcome.DISCONNECTED, completion.get(5, TimeUnit.SECONDS))
            cancelled.get(5, TimeUnit.SECONDS)
        }
        assertEquals(listOf(ExchangeOutcome.DISCONNECTED), outcomes.toList())
    }

    private fun withServer(routes: Routes.() -> Unit, test: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val stopped = AtomicBoolean()
        val listening = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val worker = thread(name = "ktserve-extension-test-loop", isDaemon = true) {
            try {
                serve(port = port, stopRequested = stopped::get, onListening = { listening.complete(Unit) }, configure = routes)
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
            worker.join()
        }
    }

    private fun client(port: Int): Socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }

    private fun Socket.send(request: String) {
        getOutputStream().write(request.encodeToByteArray())
    }

    private fun BufferedInputStream.response(status: Int): Reply {
        val headers = responseHead(status)
        val length = headers.single { it.first == "content-length" }.second.toInt()
        val body = readNBytes(length)
        assertEquals(length, body.size)
        return Reply(headers, body.decodeToString())
    }

    private fun BufferedInputStream.responseHead(status: Int): List<Pair<String, String>> {
        assertTrue(line().startsWith("HTTP/1.1 $status "))
        return buildList {
            while (true) {
                val line = line()
                if (line.isEmpty()) break
                add(line.substringBefore(':').lowercase() to line.substringAfter(':').trimStart())
            }
        }
    }

    private fun BufferedInputStream.line(): String = buildString {
        while (true) {
            val char = read()
            check(char >= 0) { "Connection closed inside response head" }
            if (char == '\n'.code) break
            append(char.toChar())
        }
    }.removeSuffix("\r")

    private data class Reply(val headers: List<Pair<String, String>>, val body: String) {
        fun headers(name: String): List<String> = headers.filter { it.first == name.lowercase() }.map { it.second }
    }
}
