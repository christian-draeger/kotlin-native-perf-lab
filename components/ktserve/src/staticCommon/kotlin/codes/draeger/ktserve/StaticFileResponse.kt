package codes.draeger.ktserve

import kotlin.uuid.Uuid

private val getHeaders = arrayOf("If-Match", "If-None-Match", "Range", "If-Range")
private val headHeaders = arrayOf("If-Match", "If-None-Match")
private val acceptRanges = HttpField("Accept-Ranges", "bytes")
private val emptyBody = ByteArray(0)
private const val MAX_BUFFERED_FILE_BYTES = 4 * 1024

internal class StaticFile(
    val path: String,
    private val contentType: String,
    private val bytes: ByteArray,
    cachePolicy: HttpField?,
) {
    // Each immutable snapshot representation receives one strong, opaque validator.
    private val tag = HttpField("ETag", "\"${Uuid.random()}\"")
    private val validators = listOfNotNull(tag, cachePolicy)
    private val transferFields = validators + acceptRanges
    private val transferFieldBytes = transferFields.sumOf { it.wireSize() }
    // Coalesce the header and small immutable bodies in the connection's buffered response path.
    private val bufferedResponse = if (bytes.size <= MAX_BUFFERED_FILE_BYTES) {
        Response(200, contentType, bytes, fields = transferFields, fieldBytes = transferFieldBytes)
    } else null
    // Larger files stream through a bounded buffer. The snapshot also owns their read-only HTTP/1.1 head.
    private val keepAliveHead = if (bufferedResponse == null) {
        Response(200, contentType, emptyBody, fields = transferFields).encodeHead("HTTP/1.1", true, bytes.size)
    } else null
    private val notModified = bodyless(304, validators)
    private val preconditionFailed = bodyless(412, listOf(tag))
    private val notSatisfiable = bodyless(416, listOf(tag, HttpField("Content-Range", "bytes */${bytes.size}")))

    fun response(request: Request): Response {
        var sawIfMatch = false
        var matchedIfMatch = false
        var ifNoneMatch = false
        var range: String? = null
        var repeatedRange = false
        var sawIfRange = false
        var matchedIfRange = true

        request.forEachMatchingHeader(if (request.method == "GET") getHeaders else headHeaders) { field, value ->
            when (field) {
                0 -> {
                    sawIfMatch = true
                    if (!matchedIfMatch) matchedIfMatch = value.matchesEntityTag(tag.value, weakComparison = false)
                }
                1 -> if (!ifNoneMatch) ifNoneMatch = value.matchesEntityTag(tag.value, weakComparison = true)
                2 -> if (range == null) range = value else repeatedRange = true
                3 -> {
                    matchedIfRange = !sawIfRange && value == tag.value
                    sawIfRange = true
                }
            }
        }

        if (sawIfMatch && !matchedIfMatch) return preconditionFailed
        if (ifNoneMatch) return notModified
        if (!repeatedRange && matchedIfRange) {
            val selected = range?.byteRange(bytes.size)
            if (selected != null) {
                if (selected.isEmpty()) return notSatisfiable
                val contentRange = HttpField("Content-Range", "bytes ${selected.first}-${selected.last}/${bytes.size}")
                return stream(206, selected.first, selected.last + 1, contentRange)
            }
        }
        return bufferedResponse ?: stream(200, 0, bytes.size)
    }

    private fun stream(status: Int, start: Int, end: Int, contentRange: HttpField? = null): Response {
        var offset = start
        // The snapshot owns the bytes. A response owns only its cursor and borrows the output buffer.
        val source = ResponseSource { buffer, limit ->
            val count = minOf(limit, end - offset)
            if (count == 0) {
                -1
            } else {
                bytes.copyInto(buffer, startIndex = offset, endIndex = offset + count)
                offset += count
                count
            }
        }
        val preparedHead = if (status == 200) keepAliveHead else null
        return Response(
            status, contentType, emptyBody, ResponseStream.Pull(end - start, source, preparedHead),
            fields = if (contentRange == null) transferFields else transferFields + contentRange,
            fieldBytes = transferFieldBytes + (contentRange?.wireSize() ?: 0),
        )
    }
}

private fun bodyless(status: Int, fields: List<HttpField>) =
    Response(status, null, emptyBody, allowedMethods = null, fields = fields, fieldBytes = fields.sumOf { it.wireSize() })

private fun HttpField.wireSize(): Long = name.length.toLong() + value.length + 4

private fun String.matchesEntityTag(tag: String, weakComparison: Boolean): Boolean {
    if (this == "*") return true
    var position = 0
    var matched = false
    while (position < length) {
        if (this[position] == ' ' || this[position] == '\t' || this[position] == ',') {
            position++
            continue
        }
        val weak = startsWith("W/", position)
        if (weak) position += 2
        if (position == length || this[position] != '"') return false
        val start = position++
        while (position < length && this[position] != '"') {
            val char = this[position++]
            if (char <= ' ' || char == '\u007F') return false
        }
        if (position == length) return false
        position++
        if ((!weak || weakComparison) && position - start == tag.length && regionMatches(start, tag, 0, tag.length)) {
            matched = true
        }
        while (position < length && (this[position] == ' ' || this[position] == '\t')) position++
        if (position < length && this[position++] != ',') return false
    }
    return matched
}

/** Null ignores malformed or unsupported ranges. An empty range is valid but unsatisfiable. */
private fun String.byteRange(size: Int): IntRange? {
    val valueStart = "bytes=".length
    if (!startsWith("bytes=", ignoreCase = true)) return null
    val dash = indexOf('-', valueStart)
    if (dash == -1) return null
    if (dash == valueStart) {
        val suffix = decimal(dash + 1, length) ?: return null
        if (suffix == 0L || size == 0) return IntRange.EMPTY
        return (size - minOf(suffix, size.toLong()).toInt()) until size
    }
    val first = decimal(valueStart, dash) ?: return null
    val openEnded = dash + 1 == length
    val last = if (openEnded) Long.MAX_VALUE else decimal(dash + 1, length) ?: return null
    if (last < first || !openEnded && first == Long.MAX_VALUE && last == first &&
        decimalGreaterThan(valueStart, dash, dash + 1)
    ) return null
    if (first >= size) return IntRange.EMPTY
    return first.toInt()..minOf(last, size.toLong() - 1).toInt()
}

private fun String.decimal(start: Int, end: Int): Long? {
    if (start == end) return null
    var result = 0L
    for (index in start until end) {
        val digit = this[index] - '0'
        if (digit !in 0..9) return null
        result = if (result > (Long.MAX_VALUE - digit) / 10) Long.MAX_VALUE else result * 10 + digit
    }
    return result
}

private fun String.decimalGreaterThan(firstStart: Int, firstEnd: Int, secondStart: Int): Boolean {
    var first = firstStart
    var second = secondStart
    while (first < firstEnd && this[first] == '0') first++
    while (second < length && this[second] == '0') second++
    if (firstEnd - first != length - second) return firstEnd - first > length - second
    while (first < firstEnd) {
        if (this[first] != this[second]) return this[first] > this[second]
        first++
        second++
    }
    return false
}
