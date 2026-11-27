package codes.draeger.ktserve

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** Read an HTTP request body incrementally from a suspending application handler. */
class RequestContent internal constructor(private val signalApplicationProgress: (() -> Unit)? = null) {
    private val chunks = Channel<ByteArray>(1)
    private var staged: ByteArray? = null

    /** Return the next owned chunk, or null after the complete request body. */
    suspend fun readChunk(): ByteArray? {
        val result = chunks.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()?.also { signalApplicationProgress?.invoke() }
    }

    internal fun offer(buffer: ByteArray, length: Int): Boolean {
        val bytes = staged ?: buffer.copyOfRange(0, length).also { staged = it }
        if (chunks.trySend(bytes).isFailure) return false
        staged = null
        return true
    }

    internal fun finish() {
        chunks.close()
    }

    internal fun cancel() {
        staged = null
        chunks.cancel()
    }
}

/** Decode the remaining, server-limited request content as one UTF-8 JSON value. */
suspend inline fun <reified T> RequestContent.receiveJson(): T = Json.decodeFromString(serializer<T>(), readUtf8())

@PublishedApi
internal suspend fun RequestContent.readUtf8(): String {
    val first = readChunk() ?: return ""
    val second = readChunk() ?: return first.decodeToString(throwOnInvalidSequence = true)
    val chunks = arrayListOf(first, second)
    var size = first.size + second.size
    while (true) {
        val chunk = readChunk() ?: break
        size += chunk.size
        chunks.add(chunk)
    }
    val bytes = ByteArray(size)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(bytes, destinationOffset = offset)
        offset += chunk.size
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}
