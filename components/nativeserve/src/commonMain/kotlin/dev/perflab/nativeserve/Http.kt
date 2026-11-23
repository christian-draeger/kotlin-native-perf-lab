package dev.perflab.nativeserve

import kotlinx.serialization.json.Json

class Request internal constructor(
    val method: String,
    val path: String,
    val query: String?,
    private val parameters: Map<String, String> = emptyMap(),
) {
    fun pathParameter(name: String): String? = parameters[name]

    fun pathInt(name: String): Int? = pathParameter(name)?.toIntOrNull()

    internal fun withParameters(values: Map<String, String>) = Request(method, path, query, values)
}

class Response internal constructor(
    val status: Int,
    internal val contentType: String?,
    internal val body: ByteArray,
    internal val stream: ResponseStream? = null,
) {
    init {
        require(status in 200..599) { "Response status must be between 200 and 599" }
    }
}

/** A response body pulled synchronously on the connection's I/O loop. */
fun interface ResponseSource : AutoCloseable {
    /**
     * Write 1 to [limit] bytes from index zero and return the count. A nonpositive
     * count before the declared length is sent ends the connection. Return promptly
     * and release any reference to [buffer] before returning.
     */
    fun read(buffer: ByteArray, limit: Int): Int

    override fun close() {}
}

internal class ResponseStream(val length: Int, val source: ResponseSource)

/**
 * Stream a body with a known byte length. Pass a fresh [source] per response.
 * Returning this response from a handler transfers ownership to NativeServe, which
 * closes the source after the final write or cancellation, including size-limit rejection.
 */
fun streamResponse(length: Int, contentType: String, source: ResponseSource, status: Int = 200): Response {
    require(length >= 0) { "Response length must be nonnegative" }
    require(status != 204 && status != 205 && status != 304) { "This response status forbids content" }
    require(contentType.isNotBlank() && contentType.all { it in ' '..'~' }) { "Invalid response Content-Type" }
    return Response(status, contentType, ByteArray(0), ResponseStream(length, source))
}

fun text(value: String, status: Int = 200): Response =
    Response(status, "text/plain; charset=UTF-8", value.encodeToByteArray())

inline fun <reified T> json(value: T, status: Int = 200): Response =
    jsonResponse(Json.encodeToString(value), status)

@PublishedApi
internal fun jsonResponse(encoded: String, status: Int): Response =
    Response(status, "application/json", encoded.encodeToByteArray())

fun badRequest(): Response = emptyResponse(400)

internal fun emptyResponse(status: Int): Response = Response(status, null, ByteArray(0))

internal fun Response.encode(version: String, keepAlive: Boolean): ByteArray {
    val payload = if (status == 204 || status == 304) ByteArray(0) else body
    return encodeHead(version, keepAlive, payload.size) + payload
}

internal fun Response.encodeHead(version: String, keepAlive: Boolean, length: Int): ByteArray {
    val reason = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        304 -> "Not Modified"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Content Too Large"
        417 -> "Expectation Failed"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        505 -> "HTTP Version Not Supported"
        else -> ""
    }
    return buildString {
        append(version).append(' ').append(status).append(' ').append(reason).append("\r\n")
        contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
        if (status == 405) append("Allow: GET\r\n")
        if (status != 204 && status != 304) append("Content-Length: ").append(length).append("\r\n")
        append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        append("\r\n")
    }.encodeToByteArray()
}
