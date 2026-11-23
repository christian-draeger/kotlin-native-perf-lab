package dev.perflab.reactor

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
    val lines = head.split("\r\n")
    val requestLine = lines.first().split(' ', limit = 4)
    if (requestLine.size != 3) return ParsedHead.Rejected(400)
    val (method, target, version) = requestLine
    if (!method.isToken() || !target.startsWith('/') || target.any { it !in '!'..'~' || it == '#' }) {
        return ParsedHead.Rejected(400)
    }
    if (version != "HTTP/1.1" && version != "HTTP/1.0") return ParsedHead.Rejected(505)

    var hasHost = false
    var hasLength = false
    var close = false
    var keepAlive = false
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon < 1) return ParsedHead.Rejected(400)
        val name = line.substring(0, colon)
        val value = line.substring(colon + 1).trim(' ', '\t')
        if (!name.isToken() || value.any { it < ' ' && it != '\t' || it == '\u007f' }) {
            return ParsedHead.Rejected(400)
        }
        when (name.lowercase()) {
            "host" -> {
                if (hasHost || value.isEmpty() || value.any { it.isWhitespace() || it in ",/@?#" }) {
                    return ParsedHead.Rejected(400)
                }
                hasHost = true
            }
            "connection" -> for (token in value.split(',')) {
                when (token.trim(' ', '\t').lowercase()) {
                    "close" -> close = true
                    "keep-alive" -> keepAlive = true
                }
            }
            "content-length" -> {
                if (hasLength || value.isEmpty() || value.any { it !in '0'..'9' }) return ParsedHead.Rejected(400)
                if (value.any { it != '0' }) return ParsedHead.Rejected(413)
                hasLength = true
            }
            "transfer-encoding" -> return ParsedHead.Rejected(400)
            "expect" -> return ParsedHead.Rejected(417)
        }
    }
    if (version == "HTTP/1.1" && !hasHost) return ParsedHead.Rejected(400)
    val queryOffset = target.indexOf('?')
    val path = if (queryOffset == -1) target else target.substring(0, queryOffset)
    val query = if (queryOffset == -1) null else target.substring(queryOffset + 1)
    return ParsedHead.Complete(
        Request(method, path, query),
        version,
        !close && (version == "HTTP/1.1" || keepAlive),
        consumed,
    )
}

private fun String.isToken(): Boolean = isNotEmpty() && all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "!#$%&'*+-.^`_|~"
}
