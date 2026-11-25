package codes.draeger.ktserve

internal enum class BodyProgress(val status: Int) {
    INCOMPLETE(0), BLOCKED(0), COMPLETE(0), INVALID(400), OVERSIZE(413), METADATA_LIMIT(431),
}

internal fun interface BodyInput {
    fun offer(bytes: ByteArray, length: Int): Boolean
}

internal interface RequestBodyDecoder {
    fun consume(input: RequestBuffer, sink: BodyInput): BodyProgress
}

internal class FixedLengthBody(private var remaining: Int) : RequestBodyDecoder {
    override fun consume(input: RequestBuffer, sink: BodyInput): BodyProgress {
        val length = minOf(remaining, input.size)
        if (length > 0) {
            if (!sink.offer(input.bytes, length)) return BodyProgress.BLOCKED
            input.consume(length)
            remaining -= length
        }
        return if (remaining == 0) BodyProgress.COMPLETE else BodyProgress.INCOMPLETE
    }
}

internal class ChunkedBody(private var bodyBudget: Int, private var metadataBudget: Int) : RequestBodyDecoder {
    private var phase = Phase.SIZE
    private var remaining = 0
    private var scan = 0

    override fun consume(input: RequestBuffer, sink: BodyInput): BodyProgress {
        while (true) {
            when (phase) {
                Phase.SIZE, Phase.TRAILERS -> {
                    val line = lineLength(input)
                    if (line < 0) {
                        return if (input.size >= minOf(metadataBudget, input.bytes.size)) BodyProgress.METADATA_LIMIT
                        else BodyProgress.INCOMPLETE
                    }
                    if (line + 2 > metadataBudget) return BodyProgress.METADATA_LIMIT
                    metadataBudget -= line + 2
                    if (phase == Phase.TRAILERS) {
                        if (line == 0) {
                            input.consume(2)
                            return BodyProgress.COMPLETE
                        }
                        if (!validTrailer(input.bytes, line)) return BodyProgress.INVALID
                    } else {
                        val size = chunkSize(input.bytes, line)
                        if (size < 0) return BodyProgress.INVALID
                        if (size > bodyBudget) return BodyProgress.OVERSIZE
                        remaining = size.toInt()
                        bodyBudget -= remaining
                        phase = if (remaining == 0) Phase.TRAILERS else Phase.DATA
                    }
                    input.consume(line + 2)
                    scan = 0
                }
                Phase.DATA -> {
                    if (input.size == 0) return BodyProgress.INCOMPLETE
                    val length = minOf(input.size, remaining)
                    if (!sink.offer(input.bytes, length)) return BodyProgress.BLOCKED
                    input.consume(length)
                    remaining -= length
                    if (remaining == 0) phase = Phase.DATA_END
                }
                Phase.DATA_END -> {
                    if (input.size < 2) return BodyProgress.INCOMPLETE
                    if (input.bytes[0] != CR || input.bytes[1] != LF) return BodyProgress.INVALID
                    input.consume(2)
                    phase = Phase.SIZE
                }
            }
        }
    }

    private fun lineLength(input: RequestBuffer): Int {
        for (index in scan until input.size - 1) {
            if (input.bytes[index] == CR && input.bytes[index + 1] == LF) return index
        }
        scan = (input.size - 1).coerceAtLeast(0)
        return -1
    }

    private enum class Phase { SIZE, DATA, DATA_END, TRAILERS }
}

private fun chunkSize(bytes: ByteArray, length: Int): Long {
    var size = 0L
    var index = 0
    while (index < length) {
        val octet = bytes[index].toInt() and 0xff
        val digit = when (octet) {
            in 48..57 -> octet - 48
            in 65..70 -> octet - 55
            in 97..102 -> octet - 87
            else -> break
        }
        if (size <= Int.MAX_VALUE) size = size * 16 + digit
        index++
    }
    if (index == 0) return -1
    if (index == length) return size
    return if (validChunkExtensions(bytes, index, length)) size else -1
}

private fun validChunkExtensions(bytes: ByteArray, start: Int, end: Int): Boolean {
    var index = start
    while (index < end) {
        index = bytes.skipOws(index, end)
        if (index == end || bytes[index++] != ';'.code.toByte()) return false
        val name = bytes.skipOws(index, end)
        index = bytes.tokenEnd(name, end)
        if (index == name) return false
        val equals = bytes.skipOws(index, end)
        if (equals < end && bytes[equals] == '='.code.toByte()) {
            val value = bytes.skipOws(equals + 1, end)
            if (value == end) return false
            index = if (bytes[value] == '"'.code.toByte()) quotedValueEnd(bytes, value + 1, end)
            else bytes.tokenEnd(value, end)
            if (index <= value) return false
        }
    }
    return true
}

private fun quotedValueEnd(bytes: ByteArray, start: Int, end: Int): Int {
    var index = start
    while (index < end) {
        val octet = bytes[index++]
        if (octet == '"'.code.toByte()) return index
        if (octet == 92.toByte()) {
            if (index == end || !bytes[index++].isFieldValue()) return -1
        } else if (!octet.isFieldValue()) return -1
    }
    return -1
}

private fun validTrailer(bytes: ByteArray, length: Int): Boolean {
    val colon = bytes.tokenEnd(0, length)
    if (colon == 0 || colon == length || bytes[colon] != ':'.code.toByte()) return false
    if (bytes.matchesAsciiIgnoreCase(0, colon, "content-length") ||
        bytes.matchesAsciiIgnoreCase(0, colon, "transfer-encoding") ||
        bytes.matchesAsciiIgnoreCase(0, colon, "connection") ||
        bytes.matchesAsciiIgnoreCase(0, colon, "host") ||
        bytes.matchesAsciiIgnoreCase(0, colon, "expect")
    ) return false
    for (index in colon + 1 until length) if (!bytes[index].isFieldValue()) return false
    return true
}
