package dev.perflab.reactor

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
) {
    init {
        require(status in 200..599) { "Response status must be between 200 and 599" }
    }
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
    val head = buildString {
        append("$version $status $reason\r\n")
        contentType?.let { append("Content-Type: $it\r\n") }
        if (status == 405) append("Allow: GET\r\n")
        if (status != 204 && status != 304) append("Content-Length: ${payload.size}\r\n")
        append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        append("\r\n")
    }.encodeToByteArray()
    return head + payload
}
