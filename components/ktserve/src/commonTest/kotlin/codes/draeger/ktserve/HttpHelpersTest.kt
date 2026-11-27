package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpHelpersTest {
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
