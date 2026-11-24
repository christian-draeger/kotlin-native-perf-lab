package dev.perflab.nativeserve

import kotlinx.coroutines.channels.Channel

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
