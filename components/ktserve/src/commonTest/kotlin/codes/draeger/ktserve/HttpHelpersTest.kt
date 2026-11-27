package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class HttpHelpersTest {
    @Test
    fun decodesQueryParametersOnDemand() {
        val raw = "q=Gr%C3%BC%C3%9Fe+%F0%9F%91%8B&tag=a%26b%3Dc&%74ag=second=part&tag&empty=&p%26q=hit&&"
        val buffer = RequestBuffer(512)
        val head = "GET /?$raw HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray()
        head.copyInto(buffer.bytes)
        buffer.append(head.size)
        val request = assertIs<ParsedHead.Complete>(buffer.parse()).request

        assertEquals(raw, request.query)
        assertEquals("Grüße+👋", request.queryParameter("q"))
        assertEquals("a&b=c", request.queryParameter("tag"))
        assertEquals(listOf("a&b=c", "second=part", ""), request.queryParameters("tag"))
        assertEquals(listOf(""), request.queryParameters("empty"))
        assertEquals(listOf("hit"), request.queryParameters("p&q"))
        assertEquals(emptyList(), request.queryParameters(""))
        assertNull(request.queryParameter("TAG"))
    }

    @Test
    fun skipsMalformedQueryPairs() {
        val request = Request("GET", "/", "tag=bad%&tag=%E2%82&ta%FFg=bad&tag=%zz&tag=ok&tag=%25")
        assertEquals("ok", request.queryParameter("tag"))
        assertEquals(listOf("ok", "%"), request.queryParameters("tag"))
        assertNull(Request("GET", "/", null).queryParameter("tag"))
        assertEquals(emptyList(), Request("GET", "/", "").queryParameters("tag"))
    }

    @Test
    fun emitsRedirectLocationWithNoBody() {
        val location = "https://example.com:8443/docs/?q=%26+%23#top"
        val reasons = mapOf(
            301 to "Moved Permanently",
            302 to "Found",
            303 to "See Other",
            307 to "Temporary Redirect",
            308 to "Permanent Redirect",
        )
        for ((status, reason) in reasons) {
            assertEquals(
                "HTTP/1.1 $status $reason\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                redirect(location, status).encode("HTTP/1.1", keepAlive = false).decodeToString(),
            )
        }
        assertEquals(308, redirect("/docs/").status)
        assertEquals(listOf(HttpField("Location", "../docs/")), redirect("../docs/").fields)
    }

    @Test
    fun rejectsMalformedRedirectFields() {
        for (location in listOf("", "/bad space", "/bad\tvalue", "/bad\r\nFoo: yes", "/ä", "/bad%", "/bad%zz", "/#a#b", "/\\host")) {
            assertFailsWith<IllegalArgumentException> { redirect(location) }
        }
        assertFailsWith<IllegalArgumentException> { redirect("/docs/", 304) }
    }
}
