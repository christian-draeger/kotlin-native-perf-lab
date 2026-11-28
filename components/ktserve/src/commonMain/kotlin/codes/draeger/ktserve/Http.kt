package codes.draeger.ktserve

import kotlinx.serialization.json.Json

class Request internal constructor(
    val method: String,
    val path: String,
    val query: String?,
    private val parameters: Map<String, String> = emptyMap(),
    val bodyLength: Int? = 0,
    private val rawFields: ByteArray? = null,
    private val decodedFields: List<HttpField> = emptyList(),
    val version: String = "HTTP/1.1",
) {
    internal var extensionContext: RequestContext? = null

    /** Read a value supplied by an installed extension for this request. */
    fun <T : Any> attribute(key: RequestAttribute<T>): T? = extensionContext?.get(key)

    fun pathParameter(name: String): String? = parameters[name]

    fun pathInt(name: String): Int? = pathParameter(name)?.toIntOrNull()

    /** Return the first decoded value for [name], or null if it is absent. See [queryParameters]. */
    fun queryParameter(name: String): String? {
        query?.forEachQueryValue(name) { return it }
        return null
    }

    /**
     * Return decoded values for [name] in query order. UTF-8 percent escapes are decoded and `+` is literal.
     * A bare name has an empty value. Empty segments and pairs with malformed escapes or UTF-8 are skipped.
     */
    fun queryParameters(name: String): List<String> {
        val rawQuery = query ?: return emptyList()
        return buildList { rawQuery.forEachQueryValue(name) { add(it) } }
    }

    /** Return the first matching field. Header names are case-insensitive. */
    fun header(name: String): String? {
        forEachField { fields, start, colon, valueStart, end ->
            if (fields.matchesAsciiIgnoreCase(start, colon, name)) return fields.decodeToString(valueStart, end)
        }
        return decodedFields.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    /** Return matching field values in wire order, with surrounding HTTP whitespace removed. */
    fun headerValues(name: String): List<String> {
        var matches: MutableList<String>? = null
        forEachHeaderValue(name) { value ->
            val values = matches ?: mutableListOf<String>().also { matches = it }
            values.add(value)
        }
        return matches ?: emptyList()
    }

    private inline fun forEachHeaderValue(name: String, visit: (String) -> Unit) {
        if (rawFields == null) {
            for (field in decodedFields) {
                if (field.name.equals(name, ignoreCase = true)) visit(field.value.trim(' ', '\t'))
            }
            return
        }
        forEachField { fields, start, colon, valueStart, end ->
            if (fields.matchesAsciiIgnoreCase(start, colon, name)) visit(fields.decodeToString(valueStart, end))
        }
    }

    /** Return the request header fields in wire order, with repeated names preserved. */
    fun headers(): List<HttpField> {
        if (rawFields == null) return decodedFields.toList()
        return buildList {
            forEachField { fields, start, colon, valueStart, end ->
                add(HttpField(fields.decodeToString(start, colon), fields.decodeToString(valueStart, end)))
            }
        }
    }

    /** Visit matching fields in wire order, decoding only the selected values. */
    internal inline fun forEachMatchingHeader(names: Array<String>, action: (Int, String) -> Unit) {
        if (rawFields == null) {
            for (field in decodedFields) {
                for (index in names.indices) {
                    if (field.name.equals(names[index], ignoreCase = true)) {
                        action(index, field.value.trim(' ', '\t'))
                        break
                    }
                }
            }
            return
        }
        forEachField { fields, start, colon, valueStart, end ->
            for (index in names.indices) {
                if (fields.matchesAsciiIgnoreCase(start, colon, names[index])) {
                    action(index, fields.decodeToString(valueStart, end))
                    break
                }
            }
        }
    }

    private inline fun forEachField(action: (ByteArray, Int, Int, Int, Int) -> Unit) {
        val fields = rawFields ?: return
        var start = 0
        while (start < fields.size) {
            val end = fields.indexOf(CR, start, fields.size)
            val colon = fields.indexOf(':'.code.toByte(), start, end)
            val valueStart = fields.skipOws(colon + 1, end)
            action(fields, start, colon, valueStart, fields.trimmedEnd(valueStart, end))
            start = end + 2
        }
    }

    internal fun withParameters(values: Map<String, String>) =
        Request(method, path, query, values, bodyLength, rawFields, decodedFields, version).also {
            it.extensionContext = extensionContext
        }
}

private inline fun String.forEachQueryValue(name: String, visit: (String) -> Unit) {
    var start = 0
    while (start < length) {
        val end = indexOf('&', start).let { if (it == -1) length else it }
        var separator = start
        while (separator < end && this[separator] != '=') separator++
        if (end > start && matchesQueryName(start, separator, name)) {
            val valueStart = if (separator < end) separator + 1 else end
            val value = if (hasQueryEscape(valueStart, end)) decodeQueryComponent(valueStart, end)
            else substring(valueStart, end)
            if (value != null) visit(value)
        }
        start = end + 1
    }
}

private fun String.hasQueryEscape(start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (this[index] == '%') return true
    }
    return false
}

private fun String.matchesQueryName(start: Int, end: Int, name: String): Boolean =
    if (hasQueryEscape(start, end)) decodeQueryComponent(start, end) == name
    else name.length == end - start && regionMatches(start, name, 0, name.length)

private fun String.decodeQueryComponent(start: Int, end: Int): String? {
    return try {
        val bytes = encodeToByteArray(start, end, throwOnInvalidSequence = true)
        var read = 0
        var write = 0
        while (read < bytes.size) {
            val value = bytes[read++]
            if (value == '%'.code.toByte()) {
                if (read + 1 >= bytes.size) return null
                val high = bytes[read++].toInt().toChar().asciiHexValue()
                val low = bytes[read++].toInt().toChar().asciiHexValue()
                if (high < 0 || low < 0) return null
                bytes[write++] = (high * 16 + low).toByte()
            } else {
                bytes[write++] = value
            }
        }
        bytes.decodeToString(endIndex = write, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun Char.asciiHexValue(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> -1
}

class Response internal constructor(
    val status: Int,
    internal val contentType: String?,
    internal val body: ByteArray,
    internal val stream: ResponseStream? = null,
    internal val allowedMethods: String? = "GET",
    internal val fields: List<HttpField> = emptyList(),
    internal val fieldBytes: Long = 0,
) {
    internal val applicationHeaderBytes: Long
        get() = fieldBytes + (contentType?.let { it.length.toLong() + 16 } ?: 0)

    init {
        require(status in 200..599) { "Response status must be between 200 and 599" }
    }

    /** Append application fields such as Cache-Control or Set-Cookie in the order provided. */
    fun withHeaders(vararg headers: HttpField): Response {
        if (headers.isEmpty()) return this
        var size = fieldBytes
        var allowed = allowedMethods
        for (field in headers) {
            field.requireApplicationField()
            size += field.name.length.toLong() + field.value.length + 4
            if (field.name.equals("allow", ignoreCase = true)) allowed = null
        }
        return withApplicationFields(fields + headers, size, allowed)
    }

    internal fun replaceApplicationFields(headers: List<HttpField>): Response {
        val size = headers.sumOf { it.name.length.toLong() + it.value.length + 4 }
        val allowed = allowedMethods?.takeUnless { headers.any { it.name.equals("allow", ignoreCase = true) } }
        return withApplicationFields(headers.toList(), size, allowed)
    }

    internal fun replaceApplicationFields(headers: PreparedResponseHeaders): Response =
        withApplicationFields(headers.fields, headers.wireBytes, allowedMethods.takeUnless { headers.hasAllow })

    private fun withApplicationFields(headers: List<HttpField>, size: Long, allowed: String?): Response {
        val updatedStream = if (stream is ResponseStream.Pull && stream.keepAliveHead != null) {
            ResponseStream.Pull(stream.length, stream.source)
        } else stream
        return Response(status, contentType, body, updatedStream, allowed, headers, size)
    }
}

/** A bounded request body consumed incrementally on the connection's I/O loop. */
interface RequestBodySink : AutoCloseable {
    /** Consume [length] bytes from index zero, then release all references to [buffer]. */
    fun accept(buffer: ByteArray, length: Int)

    /** Produce the response after the declared request body has arrived in full. */
    fun complete(): Response

    override fun close() {}
}

/** A response body pulled synchronously on the connection's I/O loop. */
fun interface ResponseSource : AutoCloseable {
    /**
     * Write 1 to [limit] bytes from index zero and return the count, or return -1
     * at end of input. An early EOF on a known-length response ends the connection.
     * Return promptly and release any reference to [buffer] before returning.
     */
    fun read(buffer: ByteArray, limit: Int): Int

    override fun close() {}
}

internal sealed class ResponseStream(val length: Int?) {
    class Pull(length: Int?, val source: ResponseSource, val keepAliveHead: ByteArray? = null) : ResponseStream(length)
    class Produce(
        length: Int?,
        val onClose: () -> Unit,
        val producer: suspend ResponseWriter.() -> Unit,
    ) : ResponseStream(length)

    fun discard() {
        when (this) {
            is Pull -> source.release()
            is Produce -> closeProducer(onClose)
        }
    }
}

/**
 * Stream a body with a known byte length. Pass a fresh [source] per response.
 * Returning this response from a handler transfers ownership to ktServe, which
 * closes the source after the final write or cancellation, including size-limit rejection.
 */
fun streamResponse(length: Int, contentType: String, source: ResponseSource, status: Int = 200): Response {
    require(length >= 0) { "Response length must be nonnegative" }
    return sourceResponse(length, contentType, source, status)
}

/** Stream an HTTP/1.1 response up to the configured size limit, using chunked framing. */
fun streamResponse(contentType: String, source: ResponseSource, status: Int = 200): Response =
    sourceResponse(null, contentType, source, status)

private fun sourceResponse(length: Int?, contentType: String, source: ResponseSource, status: Int): Response {
    require(status != 204 && status != 205 && status != 304) { "This response status forbids content" }
    requireContentType(contentType)
    return Response(status, contentType, ByteArray(0), ResponseStream.Pull(length, source))
}

/**
 * Stream an HTTP/1.x response from a suspending producer. A null length uses HTTP/1.1 chunked framing.
 * Acquire body resources inside the producer, which starts only when a response body can be sent.
 */
fun streamSuspending(
    length: Int? = null,
    contentType: String? = null,
    status: Int = 200,
    producer: suspend ResponseWriter.() -> Unit,
): Response = streamSuspending(length, contentType, status, onClose = {}, producer = producer)

/** Supply a release hook for resources acquired before the response producer can start. */
fun streamSuspending(
    length: Int? = null,
    contentType: String? = null,
    status: Int = 200,
    onClose: () -> Unit,
    producer: suspend ResponseWriter.() -> Unit,
): Response {
    require(length == null || length >= 0) { "Response length must be nonnegative" }
    require(status != 204 && status != 205 && status != 304) { "This response status forbids content" }
    requireContentType(contentType)
    return Response(status, contentType, ByteArray(0), ResponseStream.Produce(length, onClose, producer), allowedMethods = null)
}

fun text(value: String, status: Int = 200): Response =
    Response(status, "text/plain; charset=UTF-8", value.encodeToByteArray())

/** Return an encoded body. The body is owned by ktServe after the handler returns. */
fun response(body: ByteArray, status: Int = 200, contentType: String? = null): Response {
    requireContentType(contentType)
    return Response(status, contentType, body, allowedMethods = null)
}

inline fun <reified T> json(value: T, status: Int = 200): Response =
    jsonResponse(Json.encodeToString(value), status)

@PublishedApi
internal fun jsonResponse(encoded: String, status: Int): Response =
    Response(status, "application/json", encoded.encodeToByteArray())

fun badRequest(): Response = emptyResponse(400)

/** Redirect to a nonempty, ASCII URI reference. Percent-encode characters outside the URI syntax. */
fun redirect(location: String, status: Int = 308): Response {
    require(status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        "Expected a redirect status"
    }
    require(location.hasValidLocationCharacters()) { "Expected an encoded redirect location" }
    return emptyResponse(status).withHeaders(HttpField("Location", location))
}

private fun String.hasValidLocationCharacters(): Boolean {
    if (isEmpty()) return false
    var index = 0
    var fragment = false
    while (index < length) {
        val char = this[index++]
        when {
            char == '%' -> {
                if (index + 1 >= length || this[index].asciiHexValue() < 0 || this[index + 1].asciiHexValue() < 0) {
                    return false
                }
                index += 2
            }
            char == '#' -> {
                if (fragment) return false
                fragment = true
            }
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~:/?[]@!$&'()*+,;=" -> Unit
            else -> return false
        }
    }
    return true
}

internal fun emptyResponse(status: Int): Response = Response(status, null, ByteArray(0))

internal fun methodNotAllowed(methods: String): Response = Response(405, null, ByteArray(0), allowedMethods = methods)

internal fun Response.encode(version: String, keepAlive: Boolean): ByteArray {
    val payload = if (status == 204 || status == 304) ByteArray(0) else body
    return encodeHead(version, keepAlive, payload.size) + payload
}

internal const val CHUNKED_RESPONSE_LENGTH = -1
internal const val OMIT_RESPONSE_LENGTH = -2

internal fun Response.encodeHead(version: String, keepAlive: Boolean, length: Int): ByteArray {
    val reason = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        412 -> "Precondition Failed"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        417 -> "Expectation Failed"
        422 -> "Unprocessable Content"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> ""
    }
    return buildString {
        append(version).append(' ').append(status).append(' ').append(reason).append("\r\n")
        contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
        if (status == 405 && allowedMethods != null) append("Allow: ").append(allowedMethods).append("\r\n")
        for (field in fields) append(field.name).append(": ").append(field.value).append("\r\n")
        if (status != 204 && status != 304) {
            if (length == CHUNKED_RESPONSE_LENGTH) append("Transfer-Encoding: chunked\r\n")
            if (length >= 0) append("Content-Length: ").append(length).append("\r\n")
        }
        append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        append("\r\n")
    }.encodeToByteArray()
}
