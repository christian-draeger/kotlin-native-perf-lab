package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StaticFileResponseTest {
    private val contents = "abcdef"
    private val cache = HttpField("Cache-Control", "public, max-age=60")
    private val file = StaticFile("/file.txt", "text/plain", contents.encodeToByteArray(), cache)
    private val tag = response().field("ETag")!!

    @Test
    fun evaluatesStaticPreconditionsBeforeRanges() {
        val baseline = response()
        assertEquals(200, baseline.status)
        assertEquals(contents, baseline.responseText())
        assertEquals(cache.value, baseline.field(cache.name))

        val cached = response(
            HttpField("if-none-match", "\"other,tag\""),
            HttpField("IF-NONE-MATCH", "\"old\", W/$tag"),
            HttpField("Range", "bytes=0-1"),
        )
        assertNotModified(cached)
        assertNotModified(response(HttpField("If-None-Match", "*"), method = "HEAD"))

        val rejected = response(HttpField("If-Match", "W/$tag"), HttpField("If-None-Match", tag))
        assertEquals(412, rejected.status)
        assertNull(rejected.stream)
        assertEquals(206, response(HttpField("If-Match", tag), HttpField("Range", "bytes=0-1")).status)
    }

    @Test
    fun reusesBufferedResponsesForSmallFilesAndStreamsLargerOnes() {
        val small = response()
        assertNull(small.stream)
        assertSame(small, response())

        val large = StaticFile("/large.bin", "application/octet-stream", ByteArray(4097), null)
        val request = Request("GET", large.path, null)
        val full = large.response(request)
        val stream = assertIs<ResponseStream.Pull>(full.stream)
        assertEquals(4097, stream.length)
        val header = assertNotNull(stream.keepAliveHead).decodeToString()
        assertTrue("Content-Length: 4097\r\nConnection: keep-alive\r\n" in header)
        val modified = full.withHeaders(HttpField("Cache-Control", "no-cache"))
        assertNull(assertIs<ResponseStream.Pull>(modified.stream).keepAliveHead)
    }

    @Test
    fun streamsOnlyTheSelectedByteRange() {
        assertRange("bytes=1-3", "bytes 1-3/6", "bcd")
        assertRange("bytes=4-", "bytes 4-5/6", "ef")
        assertRange("bytes=-2", "bytes 4-5/6", "ef")
        assertRange("bytes=-999999999999999999999999", "bytes 0-5/6", contents)
        assertRange("bytes=2-999999999999999999999999", "bytes 2-5/6", "cdef")

        for (range in listOf("bytes=6-", "bytes=-0", "bytes=999999999999999999999999-")) {
            val rejected = response(HttpField("Range", range))
            assertEquals(416, rejected.status, range)
            assertEquals("bytes */6", rejected.field("Content-Range"), range)
            assertNull(rejected.stream, range)
        }
    }

    @Test
    fun ignoresInvalidMultipleAndConditionalRangesWhenInapplicable() {
        for (range in listOf("items=0-1", "bytes=2-1", "bytes=0-1,3-4", "bytes=+0-1", "bytes=-")) {
            assertFull(response(HttpField("Range", range)))
        }
        assertFull(response(HttpField("Range", "bytes=0-1"), HttpField("Range", "bytes=2-3")))
        assertFull(response(HttpField("Range", "bytes=0-1"), method = "HEAD"))
        for (validator in listOf("\"outdated\"", "W/$tag", "Sun, 06 Nov 1994 08:49:37 GMT")) {
            assertFull(response(HttpField("Range", "bytes=0-1"), HttpField("If-Range", validator)))
        }
        assertFull(response(HttpField("Range", "bytes=0-1"), HttpField("If-Range", tag), HttpField("If-Range", tag)))
        assertRange("bytes=0-1", "bytes 0-1/6", "ab", HttpField("If-Range", tag))
    }

    private fun response(vararg fields: HttpField, method: String = "GET") =
        file.response(Request(method, file.path, null, decodedFields = fields.toList()))

    private fun assertNotModified(response: Response) {
        assertEquals(304, response.status)
        assertEquals(tag, response.field("ETag"))
        assertEquals(cache.value, response.field(cache.name))
        assertNull(response.stream)
        assertTrue(response.body.isEmpty())
    }

    private fun assertFull(response: Response) {
        assertEquals(200, response.status)
        assertNull(response.field("Content-Range"))
        assertEquals(contents, response.responseText())
    }

    private fun assertRange(range: String, expectedRange: String, expectedBody: String, vararg fields: HttpField) {
        val response = response(HttpField("Range", range), *fields)
        assertEquals(206, response.status)
        assertEquals(expectedRange, response.field("Content-Range"))
        assertEquals("bytes", response.field("Accept-Ranges"))
        assertEquals(cache.value, response.field(cache.name))
        assertEquals(tag, response.field("ETag"))
        assertEquals(expectedBody, response.responseText())
    }

    private fun Response.field(name: String) = fields.firstOrNull { it.name == name }?.value

    private fun Response.responseText(): String {
        if (stream == null) return body.decodeToString()
        val pull = assertIs<ResponseStream.Pull>(stream)
        return buildString {
            val buffer = ByteArray(2)
            while (true) {
                val count = pull.source.read(buffer, buffer.size)
                if (count == -1) break
                assertTrue(count > 0)
                append(buffer.decodeToString(endIndex = count))
            }
            assertEquals(pull.length, length)
        }
    }
}
