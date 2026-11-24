package codes.draeger.ktserve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal const val RESPONSE_CHUNK_BYTES = 16 * 1024
internal const val SOURCE_WAITING = -2

/** Writes a response incrementally and suspends when the client cannot accept more data. */
class ResponseWriter internal constructor(
    private val chunks: Channel<ByteArray>,
    private val signalApplicationProgress: (() -> Unit)?,
) {
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val end = offset + length
        var start = offset
        while (start < end) {
            val limit = start + minOf(end - start, RESPONSE_CHUNK_BYTES)
            chunks.send(bytes.copyOfRange(start, limit))
            signalApplicationProgress?.invoke()
            start = limit
        }
    }
}

internal class AsyncResponseSource(
    scope: CoroutineScope,
    private val stream: ResponseStream.Produce,
    signalApplicationProgress: (() -> Unit)?,
) : ResponseSource {
    private val chunks = Channel<ByteArray>(1)
    private val job = scope.launch {
        try {
            stream.producer(ResponseWriter(chunks, signalApplicationProgress))
            chunks.close()
        } catch (cause: Exception) {
            chunks.close(cause)
        } finally {
            signalApplicationProgress?.invoke()
        }
    }
    private var chunk: ByteArray? = null
    private var offset = 0

    override fun read(buffer: ByteArray, limit: Int): Int {
        val current = chunk ?: nextChunk() ?: return SOURCE_WAITING
        if (current.isEmpty()) return -1
        val count = minOf(limit, current.size - offset)
        current.copyInto(buffer, startIndex = offset, endIndex = offset + count)
        offset += count
        if (offset == current.size) chunk = null
        return count
    }

    private fun nextChunk(): ByteArray? {
        val result = chunks.tryReceive()
        result.exceptionOrNull()?.let { throw it }
        offset = 0
        val bytes = result.getOrNull()
        chunk = bytes
        return if (result.isClosed) EOF else bytes
    }

    override fun close() {
        chunk = null
        chunks.cancel()
        job.cancel()
        closeProducer(stream.onClose)
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}

internal fun closeProducer(release: () -> Unit) {
    try {
        release()
    } catch (_: Exception) {
        // This exchange already released its transport-owned resources.
    }
}
