package codes.draeger.ktserve

import kotlinx.serialization.json.Json

class Request internal constructor(
    val method: String,
    val path: String,
    val query: String?,
    private val parameters: Map<String, String> = emptyMap(),
    val bodyLength: Int? = 0,
    private val rawHead: String? = null,
    private val decodedFields: List<HttpField> = emptyList(),
    val version: String = "HTTP/1.1",
) {
    fun pathParameter(name: String): String? = parameters[name]

    fun pathInt(name: String): Int? = pathParameter(name)?.toIntOrNull()

    /** Return the first matching field. Header names are case-insensitive. */
    fun header(name: String): String? {
        forEachField { head, start, colon, valueStart, end ->
            if (colon - start == name.length && head.regionMatches(start, name, 0, name.length, ignoreCase = true)) {
                return head.substring(valueStart, end)
            }
        }
        return decodedFields.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    /** Return the request head fields in wire order, with repeated names preserved. */
    fun headers(): List<HttpField> {
        if (rawHead == null) return decodedFields.toList()
        return buildList {
            forEachField { head, start, colon, valueStart, end ->
                add(HttpField(head.substring(start, colon), head.substring(valueStart, end)))
            }
        }
    }

    private inline fun forEachField(action: (String, Int, Int, Int, Int) -> Unit) {
        val head = rawHead ?: return
        val firstEnd = head.indexOf("\r\n")
        if (firstEnd < 0) return
        var start = firstEnd + 2
        while (start < head.length) {
            val end = head.lineEnd(start)
            val colon = head.indexOf(':', start)
            val valueStart = head.skipOws(colon + 1, end)
            action(head, start, colon, valueStart, head.trimmedEnd(valueStart, end))
            start = end + 2
        }
    }

    internal fun withParameters(values: Map<String, String>) =
        Request(method, path, query, values, bodyLength, rawHead, decodedFields, version)
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
        return Response(status, contentType, body, stream, allowed, fields + headers, size)
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
    class Pull(length: Int?, val source: ResponseSource) : ResponseStream(length)
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
        304 -> "Not Modified"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Content Too Large"
        417 -> "Expectation Failed"
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
