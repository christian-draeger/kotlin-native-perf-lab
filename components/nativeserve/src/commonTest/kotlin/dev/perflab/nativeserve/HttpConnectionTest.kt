package dev.perflab.nativeserve

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
