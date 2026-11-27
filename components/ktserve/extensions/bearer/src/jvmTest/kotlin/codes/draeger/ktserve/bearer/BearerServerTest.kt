package codes.draeger.ktserve.bearer

import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import java.io.BufferedReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BearerServerTest {
    @Test
    fun bearerStateBelongsToEachRequestOnAKeepAliveConnection() {
        val port = ServerSocket(0).use { it.localPort }
        val stop = AtomicBoolean()
        val listening = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        thread(name = "ktserve-bearer-test-loop", isDaemon = true) {
            try {
                serve(port = port, stopRequested = stop::get, onListening = { listening.complete(Unit) }) {
                    install(bearer())
                    get("/items/{id}") { request ->
                        val state = when (val credential = request.bearerCredential) {
                            BearerCredential.Absent -> "absent"
                            BearerCredential.Invalid -> "invalid"
                            is BearerCredential.Present -> if (credential.token == "Opaque-Token_7") "present" else "unexpected"
                        }
                        text("${request.pathParameter("id")}:$state")
                    }
                }
                finished.complete(Unit)
            } catch (failure: Throwable) {
                listening.completeExceptionally(failure)
                finished.completeExceptionally(failure)
            }
        }
        try {
            listening.get(5, TimeUnit.SECONDS)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val requests = "GET /items/1 HTTP/1.1\r\nHost: localhost\r\n" +
                    "authorization: bEaReR Opaque-Token_7 \t\r\n\r\n" +
                    "GET /items/2?access_token=ignored HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                    "GET /items/3 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic YQ==\r\n" +
                    "AUTHORIZATION: Bearer Opaque-Token_7\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(requests.encodeToByteArray())

                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                assertEquals("1:present", input.responseBody())
                assertEquals("2:absent", input.responseBody())
                assertEquals("3:invalid", input.responseBody())
                assertEquals(-1, input.read())
            }
        } finally {
            stop.set(true)
            finished.get(5, TimeUnit.SECONDS)
        }
    }

    private fun BufferedReader.responseBody(): String {
        assertTrue(checkNotNull(readLine()).startsWith("HTTP/1.1 200 "))
        var length: Int? = null
        while (true) {
            val field = checkNotNull(readLine())
            if (field.isEmpty()) break
            if (field.startsWith("Content-Length:", ignoreCase = true)) length = field.substringAfter(':').trim().toInt()
        }
        return buildString {
            repeat(checkNotNull(length)) {
                val char = read()
                check(char >= 0) { "Connection closed inside the response body" }
                append(char.toChar())
            }
        }
    }
}
