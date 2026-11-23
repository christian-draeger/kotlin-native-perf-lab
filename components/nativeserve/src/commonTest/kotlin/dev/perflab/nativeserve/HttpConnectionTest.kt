package dev.perflab.nativeserve

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun HttpConnection.receive(value: String, now: Long) {
        val bytes = value.encodeToByteArray()
        bytes.copyInto(input.bytes, input.size)
        received(bytes.size, now)
    }
}
