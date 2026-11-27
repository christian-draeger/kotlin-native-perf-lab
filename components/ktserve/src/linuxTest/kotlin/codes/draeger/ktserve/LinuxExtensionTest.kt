@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.ipv4SocketAddress
import codes.draeger.ktserve.transport.openListener
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.MSG_NOSIGNAL
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.ntohs
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket

class LinuxExtensionTest {
    @Test
    fun extensionCanAnswerBeforeDispatchAndObserveTheWrittenResponse(): Unit = runBlocking {
        val port = freePort()
        val stop = AtomicBoolean(false)
        val listening = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<ExchangeCompletion>()
        val server = launch(Dispatchers.Default) {
            serve(port = port, workers = 1, stopRequested = stop::load, onListening = { listening.complete(Unit) }) {
                install(extension("example.linux-gate") {
                    onRequest { RequestDecision.Respond(text("denied", status = 403)) }
                    onResponseHeaders { _, headers -> headers.set("X-Extension", "applied") }
                    onComplete { _, result -> completed.complete(result) }
                })
                get("/protected") { error("The extension should answer before dispatch") }
            }
        }
        try {
            withTimeout(5_000) { listening.await() }
            val response = request(port)
            assertTrue(response.startsWith("HTTP/1.1 403 "))
            assertTrue("\r\nX-Extension: applied\r\n" in response)
            assertTrue(response.endsWith("\r\n\r\ndenied"))
            val result = withTimeout(5_000) { completed.await() }
            assertEquals(403, result.status)
            assertEquals(ExchangeOutcome.COMPLETED, result.outcome)
        } finally {
            stop.store(true)
            server.join()
        }
    }

    private fun freePort(): Int = memScoped {
        val listener = openListener("127.0.0.1", port = 0)
        try {
            val address = alloc<sockaddr_in>()
            val length = alloc<UIntVar>()
            length.value = sizeOf<sockaddr_in>().convert()
            check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname failed (errno $errno)" }
            ntohs(address.sin_port).toInt()
        } finally {
            close(listener)
        }
    }

    private fun request(port: Int): String = memScoped {
        val descriptor = socket(AF_INET, SOCK_STREAM, 0)
        check(descriptor >= 0) { "socket failed (errno $errno)" }
        try {
            val address = ipv4SocketAddress("127.0.0.1", port)
            check(connect(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) {
                "connect failed (errno $errno)"
            }
            val request = "GET /protected HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".encodeToByteArray()
            request.usePinned { pinned ->
                val count = send(descriptor, pinned.addressOf(0), request.size.convert(), MSG_NOSIGNAL)
                check(count == request.size.toLong()) { "Incomplete HTTP request write: $count bytes (errno $errno)" }
            }

            val readiness = alloc<pollfd>().apply {
                fd = descriptor
                events = POLLIN.convert()
            }
            val started = TimeSource.Monotonic.markNow()
            val response = ByteArray(512)
            var received = 0
            response.usePinned { pinned ->
                while (received < response.size) {
                    val remainingMillis = 5_000 - started.elapsedNow().inWholeMilliseconds
                    check(remainingMillis > 0 && poll(readiness.ptr, 1.convert(), remainingMillis.toInt()) > 0) {
                        "No HTTP response data available (errno $errno)"
                    }
                    val count = recv(descriptor, pinned.addressOf(received), (response.size - received).convert(), 0).toInt()
                    check(count >= 0) { "recv failed (errno $errno)" }
                    if (count == 0) break
                    received += count
                }
            }
            check(received < response.size) { "HTTP response did not fit in the test buffer" }
            response.decodeToString(endIndex = received)
        } finally {
            close(descriptor)
        }
    }
}
