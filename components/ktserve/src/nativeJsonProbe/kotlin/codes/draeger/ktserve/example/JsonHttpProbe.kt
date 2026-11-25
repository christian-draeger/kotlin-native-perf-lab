@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package codes.draeger.ktserve.example

import codes.draeger.ktserve.Response
import codes.draeger.ktserve.ResponseSource
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.badRequest
import codes.draeger.ktserve.jsonResponse
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.streamResponse
import codes.draeger.ktserve.text
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.encodeToSink

internal fun runJsonHttpProbe(args: Array<String>) {
    require(args.size in 3..4) { "Use http stock|string-stream|sink-stream port [max-response-bytes]" }
    val mode = args[1]
    require(mode in setOf("stock", "string-stream", "sink-stream"))
    val maxBody = args.getOrNull(3)?.toInt() ?: 64 * 1024
    serve(port = args[2].toInt(), limits = ServerLimits(responseBodyBytes = maxBody), workers = 1) {
        get("/health") { text("ok") }
        get("/json") { encodeHttpProbe(mode, User.serializer(), benchmarkUser(42), maxBody) }
        get("/users/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            encodeHttpProbe(mode, User.serializer(), benchmarkUser(id), maxBody)
        }
        get("/catalog/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            encodeHttpProbe(mode, Catalog.serializer(), benchmarkCatalog(id), maxBody)
        }
    }
}

internal fun <T> encodeHttpProbe(mode: String, serializer: KSerializer<T>, value: T, maxBody: Int): Response =
    when (mode) {
        "stock" -> jsonResponse(Json.encodeToString(serializer, value), 200)
        "string-stream" -> {
            val bytes = Json.encodeToString(serializer, value).encodeToByteArray()
            var offset = 0
            streamResponse(bytes.size, "application/json", ResponseSource { output, limit ->
                val count = minOf(limit, bytes.size - offset)
                if (count == 0) return@ResponseSource -1
                bytes.copyInto(output, 0, offset, offset + count)
                offset += count
                count
            })
        }
        "sink-stream" -> sinkResponse(serializer, value, maxBody)
        else -> error("Unsupported HTTP JSON probe mode")
    }

private fun <T> sinkResponse(serializer: KSerializer<T>, value: T, maxBody: Int): Response {
    val encoded = Buffer()
    val collector = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            require(byteCount <= maxBody - encoded.size) { "JSON body exceeded the probe limit" }
            encoded.write(source, byteCount)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }
    try {
        collector.buffered().use { Json.encodeToSink(serializer, value, it) }
    } catch (exception: Exception) {
        encoded.clear()
        throw exception
    }
    // The HTTP stream owns this buffer through the final write or connection cancellation.
    return streamResponse(encoded.size.toInt(), "application/json", object : ResponseSource {
        override fun read(buffer: ByteArray, limit: Int): Int = encoded.readAtMostTo(buffer, endIndex = limit)

        override fun close() = encoded.clear()
    })
}
