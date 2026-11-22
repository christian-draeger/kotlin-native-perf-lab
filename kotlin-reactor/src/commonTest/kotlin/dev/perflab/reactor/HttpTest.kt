package dev.perflab.reactor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpTest {
    @Test
    fun handlesFragmentationAndPreservesNextRequest() {
        val buffer = RequestBuffer(512)
        buffer.add("GET /users/123?view=short HTTP/1.1\r\nHost: localhost\r\n\r")
        assertEquals(ParsedHead.Incomplete, buffer.parse())
        buffer.add("\nGET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        val first = assertIs<ParsedHead.Complete>(buffer.parse())
        assertEquals("/users/123", first.request.path)
        assertEquals("view=short", first.request.query)
        assertTrue(first.keepAlive)
        buffer.consume(first.consumed)
        val second = assertIs<ParsedHead.Complete>(buffer.parse())
        assertEquals("/health", second.request.path)
        assertFalse(second.keepAlive)
        buffer.consume(second.consumed)
        assertEquals(0, buffer.size)
    }

    @Test
    fun rejectsInvalidOrUnsupportedHeads() {
        assertEquals(400, rejection("GET / HTTP/1.1\r\n\r\n"))
        assertEquals(400, rejection("GET / HTTP/1.1\r\nHost: localhost\r\nHost: localhost\r\n\r\n"))
        assertEquals(413, rejection("GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\n"))
        assertEquals(400, rejection("GET / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(505, rejection("GET / HTTP/2.0\r\nHost: localhost\r\n\r\n"))
        val bounded = RequestBuffer(32)
        bounded.add("G".repeat(32))
        assertEquals(431, assertIs<ParsedHead.Rejected>(bounded.parse()).status)
    }

    @Test
    fun prefersLiteralRoutesAndSupportsTypedPathParameters() {
        val routes = Routes().apply {
            get("/users/{id}") { request ->
                val id = request.pathInt("id") ?: return@get badRequest()
                json(User(id, "benchmark-user", true))
            }
            get("/users/current") { text("current") }
            get("/bad-json") { throw SerializationException("Encoding failed") }
        }
        assertEquals("current", routes.respond(Request("GET", "/users/current", null)).body.decodeToString())
        val user = routes.respond(Request("GET", "/users/123", null))
        assertEquals("application/json", user.contentType)
        assertEquals("{\"id\":123,\"name\":\"benchmark-user\",\"active\":true}", user.body.decodeToString())
        assertEquals(400, routes.respond(Request("GET", "/users/invalid", null)).status)
        assertEquals(404, routes.respond(Request("GET", "/users/", null)).status)
        assertEquals(405, routes.respond(Request("POST", "/users/123", null)).status)
        assertEquals(500, routes.respond(Request("GET", "/bad-json", null)).status)
        assertFailsWith<IllegalArgumentException> { routes.get("/users/{other}") { text("duplicate") } }
    }

    private fun rejection(value: String): Int {
        val buffer = RequestBuffer(512)
        buffer.add(value)
        return assertIs<ParsedHead.Rejected>(buffer.parse()).status
    }

    private fun RequestBuffer.add(value: String) {
        val chunk = value.encodeToByteArray()
        chunk.copyInto(bytes, destinationOffset = size)
        append(chunk.size)
    }

    @Serializable
    private data class User(val id: Int, val name: String, val active: Boolean)
}
