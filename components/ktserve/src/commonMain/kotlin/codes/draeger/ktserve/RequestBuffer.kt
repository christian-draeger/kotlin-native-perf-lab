package codes.draeger.ktserve

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

    fun parse(allowAnyRequestBody: Boolean = false): ParsedHead {
        for (index in searchOffset..size - 4) {
            if (bytes[index] == CR && bytes[index + 1] == LF && bytes[index + 2] == CR && bytes[index + 3] == LF) {
                return bytes.parseHead(index, allowAnyRequestBody)
            }
        }
        searchOffset = (size - 3).coerceAtLeast(0)
        return if (size == bytes.size) ParsedHead.Rejected(431) else ParsedHead.Incomplete
    }
}

internal const val CR: Byte = 13
internal const val LF: Byte = 10
private const val SPACE: Byte = 32
private const val COLON: Byte = 58
private const val QUESTION: Byte = 63
private const val SLASH: Byte = 47
private const val PERCENT: Byte = 37

private fun ByteArray.parseHead(end: Int, allowAnyRequestBody: Boolean): ParsedHead {
    val methodEnd = tokenEnd(0, end)
    if (methodEnd == 0 || methodEnd == end || this[methodEnd] != SPACE) return ParsedHead.Rejected(400)
    val targetStart = methodEnd + 1
    val targetEnd = indexOf(SPACE, targetStart, end)
    if (targetEnd <= targetStart) return ParsedHead.Rejected(400)
    val versionStart = targetEnd + 1
    val lineEnd = indexOf(CR, versionStart, end)
    if (indexOf(SPACE, versionStart, lineEnd) < lineEnd || this[lineEnd + 1] != LF) return ParsedHead.Rejected(400)
    val version = when {
        matchesAscii(versionStart, lineEnd, "HTTP/1.1") -> "HTTP/1.1"
        matchesAscii(versionStart, lineEnd, "HTTP/1.0") -> "HTTP/1.0"
        else -> return ParsedHead.Rejected(505)
    }

    var pathStart = targetStart
    val authorityStart = if (this[targetStart] == SLASH) {
        -1
    } else {
        val start = when {
            targetStart + 7 < targetEnd && matchesAsciiIgnoreCase(targetStart, targetStart + 7, "http://") -> targetStart + 7
            targetStart + 8 < targetEnd && matchesAsciiIgnoreCase(targetStart, targetStart + 8, "https://") -> targetStart + 8
            else -> return ParsedHead.Rejected(400)
        }
        pathStart = start
        while (pathStart < targetEnd && this[pathStart] != SLASH && this[pathStart] != QUESTION) pathStart++
        if (!isHostAuthority(start, pathStart)) return ParsedHead.Rejected(400)
        start
    }
    var queryStart = targetEnd
    var targetIndex = pathStart
    while (targetIndex < targetEnd) {
        val octet = this[targetIndex]
        when {
            octet == QUESTION -> if (queryStart == targetEnd) queryStart = targetIndex
            octet == PERCENT -> {
                if (targetIndex + 2 >= targetEnd || !this[targetIndex + 1].isHex() || !this[targetIndex + 2].isHex()) {
                    return ParsedHead.Rejected(400)
                }
                targetIndex += 2
            }
            !octet.isPathCharacter() -> return ParsedHead.Rejected(400)
        }
        targetIndex++
    }

    var hostStart = -1
    var hostEnd = -1
    var hasLength = false
    var bodyLength = 0
    var bodyLengthOverflow = false
    var chunked = false
    var close = false
    var keepAlive = false
    val fieldsStart = lineEnd + 2
    var fieldStart = fieldsStart
    while (fieldStart < end) {
        val colon = tokenEnd(fieldStart, end)
        if (colon == fieldStart || colon == end || this[colon] != COLON) return ParsedHead.Rejected(400)
        val valueStart = skipOws(colon + 1, end)
        var fieldEnd = valueStart
        while (fieldEnd < end && this[fieldEnd] != CR) {
            if (!this[fieldEnd].isFieldValue()) return ParsedHead.Rejected(400)
            fieldEnd++
        }
        if (this[fieldEnd + 1] != LF) return ParsedHead.Rejected(400)
        val valueEnd = trimmedEnd(valueStart, fieldEnd)
        when {
            matchesAsciiIgnoreCase(fieldStart, colon, "host") -> {
                if (hostStart >= 0 || !isHostAuthority(valueStart, valueEnd)) return ParsedHead.Rejected(400)
                hostStart = valueStart
                hostEnd = valueEnd
            }
            matchesAsciiIgnoreCase(fieldStart, colon, "connection") -> {
                var option = valueStart
                while (option < valueEnd) {
                    val start = skipOws(option, valueEnd)
                    val last = tokenEnd(start, valueEnd)
                    val separator = skipOws(last, valueEnd)
                    if (separator < valueEnd && this[separator] != ','.code.toByte()) return ParsedHead.Rejected(400)
                    if (matchesAsciiIgnoreCase(start, last, "close")) close = true
                    if (matchesAsciiIgnoreCase(start, last, "keep-alive")) keepAlive = true
                    option = separator + 1
                }
            }
            matchesAsciiIgnoreCase(fieldStart, colon, "content-length") -> {
                if (hasLength || valueStart == valueEnd) return ParsedHead.Rejected(400)
                for (index in valueStart until valueEnd) {
                    val digit = this[index].toInt() - '0'.code
                    if (digit !in 0..9) return ParsedHead.Rejected(400)
                    if (bodyLength > (Int.MAX_VALUE - digit) / 10) bodyLengthOverflow = true
                    else if (!bodyLengthOverflow) bodyLength = bodyLength * 10 + digit
                }
                hasLength = true
            }
            matchesAsciiIgnoreCase(fieldStart, colon, "transfer-encoding") -> {
                if (chunked || version != "HTTP/1.1" ||
                    !allowAnyRequestBody && !matchesAscii(0, methodEnd, "POST") ||
                    !matchesAsciiIgnoreCase(valueStart, valueEnd, "chunked")
                ) return ParsedHead.Rejected(400)
                chunked = true
            }
            matchesAsciiIgnoreCase(fieldStart, colon, "expect") -> return ParsedHead.Rejected(417)
        }
        fieldStart = fieldEnd + 2
    }
    if (version == "HTTP/1.1" && hostStart < 0) return ParsedHead.Rejected(400)
    if (hasLength && chunked) return ParsedHead.Rejected(400)
    if (bodyLengthOverflow || bodyLength > 0 && !allowAnyRequestBody && !matchesAscii(0, methodEnd, "POST")) {
        return ParsedHead.Rejected(413)
    }

    // A request can outlive the receive buffer. Keep a compact owned slice of the
    // fields so accessing a header later does not decode the whole request.
    val fields = when {
        authorityStart >= 0 -> fieldsWithAuthority(fieldsStart, end, hostStart, hostEnd, authorityStart, pathStart)
        fieldsStart < end -> copyOfRange(fieldsStart, end)
        else -> null
    }
    val method = if (matchesAscii(0, methodEnd, "GET")) "GET" else decodeToString(endIndex = methodEnd)
    return ParsedHead.Complete(
        Request(
            method,
            if (pathStart == queryStart) "/" else decodeToString(pathStart, queryStart),
            if (queryStart == targetEnd) null else decodeToString(queryStart + 1, targetEnd),
            bodyLength = if (chunked) null else bodyLength, rawFields = fields, version = version,
        ),
        version,
        !close && (version == "HTTP/1.1" || keepAlive),
        end + 4,
    )
}

private fun ByteArray.fieldsWithAuthority(
    start: Int,
    end: Int,
    hostStart: Int,
    hostEnd: Int,
    authorityStart: Int,
    authorityEnd: Int,
): ByteArray {
    val authorityLength = authorityEnd - authorityStart
    if (hostStart >= 0) {
        val fields = ByteArray(end - start - (hostEnd - hostStart) + authorityLength)
        copyInto(fields, 0, start, hostStart)
        copyInto(fields, hostStart - start, authorityStart, authorityEnd)
        copyInto(fields, hostStart - start + authorityLength, hostEnd, end)
        return fields
    }
    // An HTTP/1.0 absolute-form request can supply its authority without a Host field.
    val remaining = (end - start).coerceAtLeast(0)
    val fields = ByteArray(HOST_PREFIX.size + authorityLength + if (remaining > 0) remaining + 2 else 0)
    HOST_PREFIX.copyInto(fields)
    copyInto(fields, HOST_PREFIX.size, authorityStart, authorityEnd)
    if (remaining > 0) {
        val offset = HOST_PREFIX.size + authorityLength
        fields[offset] = CR
        fields[offset + 1] = LF
        copyInto(fields, offset + 2, start, end)
    }
    return fields
}

private val HOST_PREFIX = "Host: ".encodeToByteArray()

internal fun ByteArray.indexOf(value: Byte, start: Int, end: Int): Int {
    for (index in start until end) if (this[index] == value) return index
    return end
}

internal fun ByteArray.skipOws(start: Int, end: Int): Int {
    var index = start
    while (index < end && this[index].isOws()) index++
    return index
}

internal fun ByteArray.trimmedEnd(start: Int, end: Int): Int {
    var index = end
    while (index > start && this[index - 1].isOws()) index--
    return index
}

internal fun ByteArray.tokenEnd(start: Int, end: Int): Int {
    var index = start
    while (index < end && this[index].isToken()) index++
    return index
}

internal fun ByteArray.matchesAsciiIgnoreCase(start: Int, end: Int, literal: String): Boolean {
    if (end - start != literal.length) return false
    for (index in literal.indices) {
        val left = this[start + index].toInt()
        val right = literal[index].code
        if (left != right && (left !in 65..90 || left + 32 != right) && (right !in 65..90 || right + 32 != left)) {
            return false
        }
    }
    return true
}

private fun ByteArray.matchesAscii(start: Int, end: Int, literal: String): Boolean {
    if (end - start != literal.length) return false
    for (index in literal.indices) if (this[start + index].toInt() != literal[index].code) return false
    return true
}

internal fun Byte.isOws(): Boolean = this == SPACE || this == 9.toByte()

internal fun Byte.isFieldValue(): Boolean = this == 9.toByte() || this.toInt() >= 32 && this.toInt() != 127 || this < 0

private const val TOKEN = 1
private const val PATH_CHARACTER = 2
private const val HOST_NAME_CHARACTER = 4

private val charClasses = ByteArray(128) { octet ->
    val char = octet.toChar()
    val unreserved = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~"
    var classes = if (char.isHttpToken()) TOKEN else 0
    if (unreserved || char in "!$&'()*+,;=:@/") classes = classes or PATH_CHARACTER
    if (unreserved) classes = classes or HOST_NAME_CHARACTER
    classes.toByte()
}

private fun Byte.isToken(): Boolean = this >= 0 && charClasses[toInt()].toInt() and TOKEN != 0

private fun Byte.isPathCharacter(): Boolean = this >= 0 && charClasses[toInt()].toInt() and PATH_CHARACTER != 0

private fun Byte.isHostNameCharacter(): Boolean = this >= 0 && charClasses[toInt()].toInt() and HOST_NAME_CHARACTER != 0

private fun Byte.isHex(): Boolean = this.toInt() in 48..57 || this.toInt() in 65..70 || this.toInt() in 97..102

private fun ByteArray.isHostAuthority(start: Int, end: Int): Boolean {
    if (start == end) return false
    var index = start
    if (this[index] == '['.code.toByte()) {
        val closing = indexOf(']'.code.toByte(), index + 1, end)
        if (closing == end || !isIpv6(index + 1, closing)) return false
        index = closing + 1
    } else {
        while (index < end && this[index].isHostNameCharacter()) index++
        if (index == start) return false
    }
    if (index == end) return true
    if (this[index++] != COLON || index == end) return false
    var port = 0
    while (index < end) {
        val digit = this[index++].toInt() - '0'.code
        if (digit !in 0..9) return false
        port = port * 10 + digit
        if (port > 65_535) return false
    }
    return true
}

private fun ByteArray.isIpv6(start: Int, end: Int): Boolean {
    var index = start
    var compressed = index + 1 < end && this[index] == COLON && this[index + 1] == COLON
    if (compressed) index += 2
    var groups = 0
    while (index < end) {
        val groupStart = index
        while (index < end && this[index].isHex()) index++
        if (index < end && this[index] == '.'.code.toByte()) {
            if (!isIpv4(groupStart, end)) return false
            groups += 2
            break
        }
        if (index - groupStart !in 1..4) return false
        groups++
        if (index == end) break
        if (this[index++] != COLON || index == end) return false
        if (this[index] == COLON) {
            if (compressed) return false
            compressed = true
            index++
        }
    }
    return if (compressed) groups < 8 else groups == 8
}

private fun ByteArray.isIpv4(start: Int, end: Int): Boolean {
    var index = start
    repeat(4) { part ->
        val segment = index
        var value = 0
        while (index < end && this[index].toInt() in 48..57) {
            value = value * 10 + (this[index++].toInt() - 48)
            if (value > 255 || index - segment > 3) return false
        }
        if (index == segment || index - segment > 1 && this[segment].toInt() == 48) return false
        if (part < 3 && (index == end || this[index++] != '.'.code.toByte())) return false
    }
    return index == end
}
