package dev.perflab.nativeserve

internal sealed interface ParsedHead {
    data object Incomplete : ParsedHead
    data class Rejected(val status: Int) : ParsedHead
    data class Complete(
        val request: Request,
        val version: String,
        val keepAlive: Boolean,
        val consumed: Int,
    ) : ParsedHead
}

internal class RequestBuffer(capacity: Int) {
    val bytes = ByteArray(capacity)
    var size = 0
        private set
    private var searchOffset = 0

    fun append(count: Int) {
        require(count in 0..(bytes.size - size))
        size += count
    }

    fun consume(count: Int) {
        require(count in 0..size)
        bytes.copyInto(bytes, endIndex = size, startIndex = count)
        size -= count
        searchOffset = 0
    }

    fun parse(): ParsedHead {
        for (index in searchOffset..size - 4) {
            if (bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte() &&
                bytes[index + 2] == 13.toByte() && bytes[index + 3] == 10.toByte()
            ) {
                return parseHead(bytes.decodeToString(endIndex = index), index + 4)
            }
        }
        searchOffset = (size - 3).coerceAtLeast(0)
        return if (size == bytes.size) ParsedHead.Rejected(431) else ParsedHead.Incomplete
    }
}

private fun parseHead(head: String, consumed: Int): ParsedHead {
    val requestLineEnd = head.lineEnd(0)
    val methodEnd = head.indexOf(' ')
    if (methodEnd !in 1 until requestLineEnd || !head.isToken(0, methodEnd)) return ParsedHead.Rejected(400)
    val targetStart = methodEnd + 1
    val targetEnd = head.indexOf(' ', targetStart)
    if (targetEnd !in (targetStart + 1) until requestLineEnd || head[targetStart] != '/') return ParsedHead.Rejected(400)
    val versionStart = targetEnd + 1
    if (head.indexOf(' ', versionStart) in versionStart until requestLineEnd) return ParsedHead.Rejected(400)

    var queryStart = targetEnd
    for (index in targetStart until targetEnd) {
        val char = head[index]
        if (char !in '!'..'~' || char == '#') return ParsedHead.Rejected(400)
        if (char == '?' && queryStart == targetEnd) queryStart = index
    }
    val version = when {
        head.matches(versionStart, requestLineEnd, "HTTP/1.1") -> "HTTP/1.1"
        head.matches(versionStart, requestLineEnd, "HTTP/1.0") -> "HTTP/1.0"
        else -> return ParsedHead.Rejected(505)
    }

    var hasHost = false
    var hasLength = false
    var close = false
    var keepAlive = false
    var fieldStart = requestLineEnd + 2
    while (fieldStart < head.length) {
        val fieldEnd = head.lineEnd(fieldStart)
        val colon = head.indexOf(':', fieldStart)
        if (colon !in (fieldStart + 1) until fieldEnd || !head.isToken(fieldStart, colon)) return ParsedHead.Rejected(400)
        val valueStart = head.skipOws(colon + 1, fieldEnd)
        val valueEnd = head.trimmedEnd(valueStart, fieldEnd)
        for (index in valueStart until valueEnd) {
            val char = head[index]
            if (char < ' ' && char != '\t' || char == '\u007f') return ParsedHead.Rejected(400)
        }
        when {
            head.matches(fieldStart, colon, "host", ignoreCase = true) -> {
                if (hasHost || valueStart == valueEnd) return ParsedHead.Rejected(400)
                for (index in valueStart until valueEnd) {
                    val char = head[index]
                    if (char.isWhitespace() || char in ",/@?#") return ParsedHead.Rejected(400)
                }
                hasHost = true
            }
            head.matches(fieldStart, colon, "connection", ignoreCase = true) -> {
                var tokenStart = valueStart
                for (index in valueStart..valueEnd) {
                    if (index == valueEnd || head[index] == ',') {
                        val start = head.skipOws(tokenStart, index)
                        val end = head.trimmedEnd(start, index)
                        if (head.matches(start, end, "close")) close = true
                        if (head.matches(start, end, "keep-alive")) keepAlive = true
                        tokenStart = index + 1
                    }
                }
            }
            head.matches(fieldStart, colon, "content-length", ignoreCase = true) -> {
                if (hasLength || valueStart == valueEnd) return ParsedHead.Rejected(400)
                var hasBody = false
                for (index in valueStart until valueEnd) {
                    val char = head[index]
                    if (char !in '0'..'9') return ParsedHead.Rejected(400)
                    if (char != '0') hasBody = true
                }
                if (hasBody) return ParsedHead.Rejected(413)
                hasLength = true
            }
            head.matches(fieldStart, colon, "transfer-encoding", ignoreCase = true) -> return ParsedHead.Rejected(400)
            head.matches(fieldStart, colon, "expect", ignoreCase = true) -> return ParsedHead.Rejected(417)
        }
        fieldStart = fieldEnd + 2
    }
    if (version == "HTTP/1.1" && !hasHost) return ParsedHead.Rejected(400)
    val path = head.substring(targetStart, queryStart)
    val query = if (queryStart == targetEnd) null else head.substring(queryStart + 1, targetEnd)
    return ParsedHead.Complete(
        Request(head.substring(0, methodEnd), path, query),
        version,
        !close && (version == "HTTP/1.1" || keepAlive),
        consumed,
    )
}

private fun String.lineEnd(start: Int): Int = indexOf("\r\n", start).let { if (it < 0) length else it }

private fun String.matches(start: Int, end: Int, literal: String, ignoreCase: Boolean = false): Boolean =
    end - start == literal.length && regionMatches(start, literal, 0, literal.length, ignoreCase)

private fun String.skipOws(start: Int, end: Int): Int {
    var index = start
    while (index < end && (this[index] == ' ' || this[index] == '\t')) index++
    return index
}

private fun String.trimmedEnd(start: Int, end: Int): Int {
    var index = end
    while (index > start && (this[index - 1] == ' ' || this[index - 1] == '\t')) index--
    return index
}

private fun String.isToken(start: Int, end: Int): Boolean {
    for (index in start until end) {
        val char = this[index]
        if (char !in 'A'..'Z' && char !in 'a'..'z' && char !in '0'..'9' && char !in "!#$%&'*+-.^`_|~") return false
    }
    return start < end
}
