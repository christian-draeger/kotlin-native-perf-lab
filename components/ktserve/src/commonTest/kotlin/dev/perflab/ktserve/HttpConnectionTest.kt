package dev.perflab.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpConnectionTest {
    private val request = "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n"

    @Test
    fun pendingOutputBlocksPipelinedDispatchAndDrainsBeforeClose() {
        var handled = 0
        val routes = Routes().apply {
            get("/health") {
                handled++
                text("ok")
            }
        }
        val connection = HttpConnection(0, ServerLimits())
        connection.receive(request.repeat(2), now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        val size = assertNotNull(connection.output).size

        assertTrue(connection.wrote(1, now = 2))
        assertEquals(1, connection.outputOffset)
        assertTrue(connection.prepareResponse(routes, now = 2))
        assertEquals(1, handled)

        assertTrue(connection.beginDrain())
        assertFalse(connection.wrote(size - 1, now = 3))
        assertNull(connection.output)
        assertEquals(request.length, connection.input.size)
        assertEquals(1, handled)
    }

    @Test
    fun aPartialHeadHasItsOwnDeadlineDespiteReadProgress() {
        val connection = HttpConnection(0, ServerLimits(requestHeadMillis = 5, idleMillis = 50))
        connection.receive("GET /health", now = 2)
        connection.receive(" HTTP/1.1", now = 6)
        assertFalse(connection.expired(now = 6))
        assertTrue(connection.expired(now = 7))
    }

    @Test
    fun aBufferedPartialHeadStartsItsDeadlineWhenOutputCompletes() {
        val routes = Routes().apply { get("/health") { text("ok") } }
        val connection = HttpConnection(0, ServerLimits(requestHeadMillis = 5, idleMillis = 50))
        connection.receive(request + "GET /heal", now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        assertFalse(connection.expired(now = 7))
        assertTrue(connection.wrote(connection.outputLength, now = 8))
        assertFalse(connection.prepareResponse(routes, now = 8))
        assertFalse(connection.expired(now = 12))
        assertTrue(connection.expired(now = 13))
    }

    @Test
    fun pullsTheNextStreamChunkOnlyAfterTheCurrentWriteCompletes() {
        val source = TestSource { buffer, limit ->
            buffer.fill('s'.code.toByte(), toIndex = limit)
            limit
        }
        var handled = 0
        val routes = Routes().apply {
            get("/health") {
                handled++
                if (handled == 1) streamResponse(16 * 1024 + 3, "text/plain", source) else text("ok")
            }
        }
        val connection = HttpConnection(0, ServerLimits(requestHeadMillis = 5, idleMillis = 50))
        connection.receive(request.repeat(2), now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        assertTrue(assertNotNull(connection.output).decodeToString().contains("Content-Length: 16387\r\n"))
        val headLength = connection.outputLength
        assertTrue(connection.wrote(1, now = 2))
        assertEquals(0, source.reads)
        assertTrue(connection.wrote(headLength - 1, now = 3))
        assertEquals(1, source.reads)
        assertEquals(16 * 1024, connection.outputLength)
        assertTrue(connection.wrote(1, now = 4))
        assertFalse(connection.expired(now = 8))
        assertTrue(connection.prepareResponse(routes, now = 8))
        assertEquals(1, handled)
        assertEquals(1, source.reads)

        assertTrue(connection.wrote(16 * 1024 - 1, now = 9))
        assertEquals(2, source.reads)
        assertEquals(3, connection.outputLength)
        assertEquals(0, connection.outputOffset)
        assertEquals(0, source.closes)
        assertTrue(connection.wrote(3, now = 10))
        assertEquals(1, source.closes)
        assertTrue(connection.prepareResponse(routes, now = 10))
        assertEquals(2, handled)
        assertTrue(assertNotNull(connection.output).decodeToString().endsWith("\r\n\r\nok"))
    }

    @Test
    fun sizeRejectionAndEmptyStreamReleaseTheSourceWithoutReadingIt() {
        for (length in listOf(0, 5)) {
            val source = TestSource { _, _ -> error("Unexpected read") }
            val connection = HttpConnection(0, ServerLimits(responseBodyBytes = 4))
            connection.startStream(length, source)
            val head = assertNotNull(connection.output).decodeToString()
            if (length == 0) {
                assertTrue(head.startsWith("HTTP/1.1 200 "))
                assertTrue(head.contains("Content-Length: 0\r\n"))
                assertTrue(connection.wrote(connection.outputLength, now = 2))
            } else {
                assertTrue(head.startsWith("HTTP/1.1 500 "))
                assertFalse(connection.wrote(connection.outputLength, now = 2))
            }
            assertEquals(0, source.reads)
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun aResponseOverTheHeaderLimitClosesItsSourceBeforeSending() {
        val source = TestSource { _, _ -> error("Unexpected read") }
        val routes = Routes().apply {
            get("/health") {
                streamResponse(1, "text/plain", source)
                    .withHeaders(HttpField("X-Large", "x".repeat(64)))
            }
        }
        val connection = HttpConnection(0, ServerLimits(responseHeaderBytes = 64))
        connection.receive(request, now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        assertTrue(assertNotNull(connection.output).decodeToString().startsWith("HTTP/1.1 500 "))
        assertEquals(0, source.reads)
        assertEquals(1, source.closes)
    }

    @Test
    fun theHttp10StreamKeepsItsDeclaredLengthAndClosesAfterWriting() {
        val routes = Routes().apply {
            get("/health") {
                streamResponse(1, "text/plain", ResponseSource { buffer, _ ->
                    buffer[0] = 's'.code.toByte()
                    1
                })
            }
        }
        val connection = HttpConnection(0, ServerLimits())
        connection.receive("GET /health HTTP/1.0\r\n\r\n", now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        assertEquals(
            "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1\r\nConnection: close\r\n\r\n",
            assertNotNull(connection.output).decodeToString(),
        )
        assertTrue(connection.wrote(connection.outputLength, now = 2))
        assertEquals("s", assertNotNull(connection.output).decodeToString())
        assertFalse(connection.wrote(1, now = 3))
    }

    @Test
    fun aShortOrFailedSourceEndsTheConnectionAndClosesExactlyOnce() {
        val invalidReads: List<(ByteArray, Int) -> Int> = listOf(
            { _, _ -> -1 }, { _, _ -> 0 }, { _, limit -> limit + 1 }, { _, _ -> error("Source failed") },
        )
        for (read in invalidReads) {
            val source = TestSource(read)
            val connection = HttpConnection(0, ServerLimits())
            connection.startStream(5, source)
            assertFalse(connection.wrote(connection.outputLength, now = 2))
            assertNull(connection.output)
            connection.closeResponse()
            assertEquals(1, source.reads)
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun drainCompletesTheStreamAndCancellationReleasesIt() {
        val drained = TestSource { _, limit -> limit }
        val connection = HttpConnection(0, ServerLimits())
        connection.startStream(7, drained)
        assertTrue(connection.beginDrain())
        assertTrue(connection.wrote(connection.outputLength, now = 2))
        assertFalse(connection.wrote(7, now = 3))
        assertEquals(1, drained.closes)

        val cancelled = TestSource { _, limit -> limit }
        val interrupted = HttpConnection(0, ServerLimits())
        interrupted.startStream(10, cancelled)
        assertTrue(interrupted.wrote(interrupted.outputLength, now = 3))
        interrupted.closeResponse()
        interrupted.closeResponse()
        assertEquals(1, cancelled.closes)
    }

    @Test
    fun invalidStreamMetadataLeavesOwnershipWithTheCaller() {
        val source = TestSource { _, _ -> error("Unexpected read") }
        assertFailsWith<IllegalArgumentException> { streamResponse(-1, "text/plain", source) }
        assertFailsWith<IllegalArgumentException> { streamResponse(1, "text/plain\r\nInjected: yes", source) }
        assertFailsWith<IllegalArgumentException> { streamResponse(1, "text/plain", source, status = 204) }
        assertEquals(0, source.closes)
    }

    @Test
    fun chunkedOutputHonorsPartialFramesAndKeepsTheNextRequestQueued() {
        val parts = ArrayDeque(listOf("abc", "de"))
        val source = TestSource { buffer, _ ->
            val part = parts.removeFirstOrNull()?.encodeToByteArray()
            if (part == null) -1 else {
                part.copyInto(buffer)
                part.size
            }
        }
        val connection = HttpConnection(0, ServerLimits(responseBodyBytes = 5))
        val routes = Routes().apply { get("/health") { streamResponse("text/plain", source) } }
        connection.receive(request.repeat(2), now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        val head = connection.pendingOutput()
        assertTrue(head.contains("Transfer-Encoding: chunked\r\n"))
        assertFalse(head.contains("Content-Length:"))
        assertTrue(connection.wrote(connection.outputLength, now = 2))
        assertEquals("3\r\n", connection.pendingOutput())
        assertTrue(connection.wrote(1, now = 2))
        assertEquals(1, source.reads)
        assertTrue(connection.wrote(2, now = 2))
        for (frame in listOf("abc", "\r\n2\r\n", "de", "\r\n0\r\n\r\n")) {
            assertEquals(frame, connection.pendingOutput())
            assertEquals(0, source.closes)
            assertTrue(connection.wrote(connection.outputLength, now = 2))
        }
        assertNull(connection.output)
        assertEquals(request.length, connection.input.size)
        assertEquals(3, source.reads)
        assertEquals(1, source.closes)
    }

    @Test
    fun emptyChunkedBodySendsATerminatorAndOverflowClosesTheConnection() {
        val empty = TestSource { _, _ -> -1 }
        val connection = HttpConnection(0, ServerLimits(responseBodyBytes = 4))
        val emptyRoutes = Routes().apply { get("/health") { streamResponse("text/plain", empty) } }
        connection.receive(request, now = 1)
        assertTrue(connection.prepareResponse(emptyRoutes, now = 1))
        assertTrue(connection.wrote(connection.outputLength, now = 2))
        assertEquals("0\r\n\r\n", connection.pendingOutput())
        assertTrue(connection.wrote(connection.outputLength, now = 3))
        assertEquals(1, empty.closes)

        val overflow = TestSource { _, limit -> limit }
        val overflowRoutes = Routes().apply { get("/health") { streamResponse("text/plain", overflow) } }
        connection.receive(request, now = 4)
        assertTrue(connection.prepareResponse(overflowRoutes, now = 4))
        assertTrue(connection.wrote(connection.outputLength, now = 5))
        assertEquals("4\r\n", connection.pendingOutput())
        assertTrue(connection.wrote(connection.outputLength, now = 5))
        assertEquals(4, connection.outputLength)
        assertFalse(connection.wrote(4, now = 6))
        assertNull(connection.output)
        assertEquals(1, overflow.closes)
    }

    @Test
    fun rejectsAnUnknownLengthStreamOnHttp10BeforeReadingIt() {
        val source = TestSource { _, _ -> error("Unexpected read") }
        val routes = Routes().apply { get("/health") { streamResponse("text/plain", source) } }
        val connection = HttpConnection(0, ServerLimits())
        connection.receive("GET /health HTTP/1.0\r\n\r\n", now = 1)
        assertTrue(connection.prepareResponse(routes, now = 1))
        assertTrue(connection.pendingOutput().startsWith("HTTP/1.0 500 "))
        assertEquals(0, source.reads)
        assertEquals(1, source.closes)
        assertFalse(connection.wrote(connection.outputLength, now = 2))
    }

    @Test
    fun consumesPostInFragmentsAndSeparatesTheFollowingRequest() {
        val pieces = mutableListOf<String>()
        var closes = 0
        var health = 0
        val routes = Routes().apply {
            postStreaming("/upload/{name}") { request ->
                assertEquals("sample", request.pathParameter("name"))
                assertEquals(5, request.bodyLength)
                object : RequestBodySink {
                    override fun accept(buffer: ByteArray, length: Int) {
                        pieces += buffer.decodeToString(endIndex = length)
                    }

                    override fun complete(): Response = text(pieces.joinToString(""))

                    override fun close() {
                        closes++
                    }
                }
            }
            get("/health") {
                health++
                text("ok")
            }
        }
        val connection = HttpConnection(0, ServerLimits())
        connection.receive("POST /upload/sample HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nab", now = 1)
        assertFalse(connection.prepareResponse(routes, now = 1))
        assertEquals(listOf("ab"), pieces)
        connection.receive("cde$request", now = 2)
        assertTrue(connection.prepareResponse(routes, now = 2))
        assertTrue(connection.pendingOutput().endsWith("\r\n\r\nabcde"))
        assertEquals(listOf("ab", "cde"), pieces)
        assertEquals(1, closes)
        assertEquals(0, health)
        assertTrue(connection.wrote(connection.outputLength, now = 3))
        assertTrue(connection.prepareResponse(routes, now = 3))
        assertEquals(1, health)
        connection.close()
        assertEquals(1, closes)
    }

    @Test
    fun oversizePostDoesNotOpenASinkAndAnInterruptedBodyClosesItsSink() {
        var opens = 0
        var closes = 0
        val routes = Routes().apply {
            postStreaming("/upload") {
                opens++
                object : RequestBodySink {
                    override fun accept(buffer: ByteArray, length: Int) {}
                    override fun complete(): Response = error("Incomplete body must not finish")
                    override fun close() { closes++ }
                }
            }
        }
        val head = "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n"
        val rejected = HttpConnection(0, ServerLimits(requestBodyBytes = 4))
        rejected.receive(head, now = 1)
        assertTrue(rejected.prepareResponse(routes, now = 1))
        assertTrue(rejected.pendingOutput().startsWith("HTTP/1.1 413 "))
        assertEquals(0, opens)

        val partial = HttpConnection(0, ServerLimits(requestBodyMillis = 5, idleMillis = 50))
        partial.receive(head + "a", now = 1)
        assertFalse(partial.prepareResponse(routes, now = 1))
        partial.receive("b", now = 5)
        assertFalse(partial.prepareResponse(routes, now = 5))
        assertFalse(partial.expired(now = 5))
        assertTrue(partial.expired(now = 6))
        assertEquals(1, opens)
        assertEquals(0, closes)
        partial.close()
        partial.close()
        assertEquals(1, closes)
    }

    @Test
    fun decodesFragmentedChunkedRequestsBeforeDispatchingTheNextHead() {
        var size = 0
        var closes = 0
        val routes = Routes().apply {
            postStreaming("/upload") { request ->
                assertNull(request.bodyLength)
                object : RequestBodySink {
                    override fun accept(buffer: ByteArray, length: Int) { size += length }
                    override fun complete(): Response = text(size.toString())
                    override fun close() { closes++ }
                }
            }
            get("/health") { text("ok") }
        }
        val connection = HttpConnection(0, ServerLimits(requestBodyBytes = 5))
        val partial = listOf(
            "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n3;name=value\r",
            "\nab", "c\r", "\n2\r\nde\r\n0\r\nX-Trailer: yes\r\n\r",
        )
        for (fragment in partial) {
            connection.receive(fragment, now = 1)
            assertFalse(connection.prepareResponse(routes, now = 1))
        }
        assertEquals(5, size)
        assertEquals(0, closes)
        connection.receive("\n$request", now = 2)
        assertTrue(connection.prepareResponse(routes, now = 2))
        assertTrue(connection.pendingOutput().endsWith("\r\n\r\n5"))
        assertEquals(1, closes)
        assertTrue(connection.wrote(connection.outputLength, now = 3))
        assertTrue(connection.prepareResponse(routes, now = 3))
        assertTrue(connection.pendingOutput().endsWith("\r\n\r\nok"))
    }

    @Test
    fun invalidChunkedFramingRetiresTheSinkWithoutCompletingIt() {
        var closed = 0
        val routes = Routes().apply {
            postStreaming("/upload") {
                object : RequestBodySink {
                    override fun accept(buffer: ByteArray, length: Int) {}
                    override fun complete(): Response = error("Rejected requests cannot complete")
                    override fun close() { closed++ }
                }
            }
        }
        val malformed = mapOf("1\r\nx\n\r" to 400, "0\r\nContent-Length: 1\r\n\r\n" to 400, "4\r\n" to 413)
        for ((body, status) in malformed) {
            val connection = HttpConnection(0, ServerLimits(requestBodyBytes = 3))
            val header = "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
            connection.receive(header + body, now = 1)
            assertTrue(connection.prepareResponse(routes, now = 1))
            assertTrue(connection.pendingOutput().startsWith("HTTP/1.1 $status "))
            assertFalse(connection.wrote(connection.outputLength, now = 2))
            connection.close()
        }
        assertEquals(malformed.size, closed)
    }

    private fun HttpConnection.pendingOutput(): String =
        assertNotNull(output).decodeToString(endIndex = outputLength)

    private fun HttpConnection.startStream(length: Int, source: ResponseSource) {
        val routes = Routes().apply { get("/health") { streamResponse(length, "text/plain", source) } }
        receive(request, now = 1)
        assertTrue(prepareResponse(routes, now = 1))
    }

    private fun HttpConnection.receive(value: String, now: Long) {
        val bytes = value.encodeToByteArray()
        bytes.copyInto(input.bytes, input.size)
        received(bytes.size, now)
    }

    private class TestSource(private val onRead: (ByteArray, Int) -> Int) : ResponseSource {
        var reads = 0
        var closes = 0

        override fun read(buffer: ByteArray, limit: Int): Int {
            reads++
            return onRead(buffer, limit)
        }

        override fun close() {
            closes++
        }
    }
}
