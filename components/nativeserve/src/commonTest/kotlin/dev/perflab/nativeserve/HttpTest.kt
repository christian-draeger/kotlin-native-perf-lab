package dev.perflab.nativeserve

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun parsesHeaderRangesAndPreservesTheRequestTarget() {
        val buffer = RequestBuffer(512)
        buffer.add("GET /users/123?view=short?raw HTTP/1.0\r\nhOsT:\tlocalhost \t\r\n" +
            "cOnTeNt-LeNgTh:\t000 \r\nConnection: , keep-alive, \tother \t\r\nX-Description: Grüße\r\n\r\n")
        val request = assertIs<ParsedHead.Complete>(buffer.parse())
        assertEquals("GET", request.request.method)
        assertEquals("/users/123", request.request.path)
        assertEquals("view=short?raw", request.request.query)
        assertEquals("HTTP/1.0", request.version)
        assertEquals(buffer.size, request.consumed)
        assertTrue(request.keepAlive)

        val headerless = RequestBuffer(64)
        headerless.add("GET /? HTTP/1.0\r\n\r\n")
        val emptyQuery = assertIs<ParsedHead.Complete>(headerless.parse())
        assertEquals("/", emptyQuery.request.path)
        assertEquals("", emptyQuery.request.query)
        assertFalse(emptyQuery.keepAlive)

        val closes = RequestBuffer(128)
        closes.add("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, close, keep-alive\r\n\r\n")
        assertFalse(assertIs<ParsedHead.Complete>(closes.parse()).keepAlive)
    }

    @Test
    fun keepsRequestLineAndHeaderBoundariesSeparate() {
        val cases = listOf(
            "GET / HTTP/1.1 \r\nHost: localhost" to 400,
            "GET / \r\nHost: localhost" to 505,
            "GET / HTTP/1.1\r\nHost\r\nX-Other: value" to 400,
            "GET / HTTP/1.1\r\nHost: local\u00a0host" to 400,
            "GET / HTTP/1.1\r\nHost: localhost\r\nX-Other: x\u007fy" to 400,
            "GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\ncontent-length: 0" to 400,
            "GET / HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue" to 417,
        )
        for ((head, expected) in cases) assertEquals(expected, rejection("$head\r\n\r\n"), head)
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

    @Test
    fun jsonWireBytesStayIdenticalAcrossRepeatedResponses() {
        val payload = "{\"id\":123,\"name\":\"Grüße\",\"active\":true}"
        val bodyLength = payload.encodeToByteArray().size
        repeat(20) {
            val response = json(User(123, "Grüße", true), status = 201)
            assertContentEquals(
                ("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: $bodyLength\r\n" +
                    "Connection: keep-alive\r\n\r\n$payload").encodeToByteArray(),
                response.encode("HTTP/1.1", keepAlive = true),
            )
            assertContentEquals(
                ("HTTP/1.0 201 Created\r\nContent-Type: application/json\r\nContent-Length: $bodyLength\r\n" +
                    "Connection: close\r\n\r\n$payload").encodeToByteArray(),
                response.encode("HTTP/1.0", keepAlive = false),
            )
        }
    }

    @Test
    fun bodylessAndMethodErrorWireBytesStayIdentical() {
        val payload = User(123, "Grüße", true)
        for ((status, reason) in listOf(204 to "No Content", 304 to "Not Modified")) {
            assertContentEquals(
                ("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                    "Connection: close\r\n\r\n").encodeToByteArray(),
                json(payload, status).encode("HTTP/1.1", keepAlive = false),
            )
        }
        assertContentEquals(
            ("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nContent-Length: 0\r\n" +
                "Connection: close\r\n\r\n").encodeToByteArray(),
            emptyResponse(405).encode("HTTP/1.1", keepAlive = false),
        )
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
