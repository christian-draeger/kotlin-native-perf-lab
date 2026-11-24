package dev.perflab.nativeserve

private const val RESPONSE_CHUNK_BYTES = 16 * 1024
private val EMPTY_CHUNKED_END = "0\r\n\r\n".encodeToByteArray()
private val CHUNKED_END = "\r\n0\r\n\r\n".encodeToByteArray()

class ServerLimits(
    val connections: Int = 1024,
    val headerBytes: Int = 16 * 1024,
    val responseBodyBytes: Int = 64 * 1024,
    val idleMillis: Long = 30_000,
    val requestHeadMillis: Long = 5_000,
    val drainMillis: Long = 2_000,
) {
    init {
        require(connections > 0 && headerBytes >= 64 && responseBodyBytes > 0)
        require(idleMillis > 0 && requestHeadMillis > 0 && drainMillis > 0)
    }
}

internal class HttpConnection(connectedAt: Long, private val limits: ServerLimits) {
    val input = RequestBuffer(limits.headerBytes)
    var output: ByteArray? = null
        private set
    var outputOffset = 0
        private set
    var outputLength = 0
        private set
    private var stream: PendingStream? = null
    private var closeAfterWrite = false
    private var lastActivity = connectedAt
    private var headStarted: Long? = null

    fun expired(now: Long): Boolean =
        now - lastActivity >= limits.idleMillis ||
            headStarted?.let { now - it >= limits.requestHeadMillis } == true

    fun beginDrain(): Boolean {
        closeAfterWrite = true
        return output != null
    }

    fun received(count: Int, now: Long) {
        lastActivity = now
        if (input.size == 0) headStarted = now
        input.append(count)
    }

    fun prepareResponse(routes: Routes, now: Long): Boolean {
        if (output != null) return true
        when (val head = input.parse()) {
            ParsedHead.Incomplete -> {
                // Pipelined bytes start their deadline when the previous response lets us parse them.
                if (input.size > 0 && headStarted == null) headStarted = now
                return false
            }
            is ParsedHead.Rejected -> {
                headStarted = null
                queue(emptyResponse(head.status), "HTTP/1.1", keepAlive = false)
            }
            is ParsedHead.Complete -> {
                input.consume(head.consumed)
                headStarted = null
                val response = routes.respond(head.request)
                val responseStream = response.stream
                val chunked = responseStream != null && responseStream.length == null
                if ((responseStream?.length ?: response.body.size) > limits.responseBodyBytes ||
                    chunked && head.version != "HTTP/1.1"
                ) {
                    responseStream?.source?.release()
                    queue(emptyResponse(500), head.version, keepAlive = false)
                } else {
                    queue(response, head.version, head.keepAlive)
                }
            }
        }
        return true
    }

    fun wrote(count: Int, now: Long): Boolean {
        checkNotNull(output)
        lastActivity = now
        outputOffset += count
        if (outputOffset < outputLength) return true
        output = null
        val pending = stream ?: return !closeAfterWrite
        if (pending.phase == ChunkPhase.END || !pending.chunked && pending.remaining == 0) {
            closeResponse()
            return !closeAfterWrite
        }
        if (pending.phase == ChunkPhase.PAYLOAD) {
            pending.phase = ChunkPhase.SOURCE
            setOutput(pending.buffer, pending.chunkLength)
            return true
        }
        return pull(pending)
    }

    private fun pull(pending: PendingStream): Boolean {
        // At the cap, probe for EOF once so a response ending exactly at the limit can finish.
        val limit = minOf(pending.remaining, pending.buffer.size).coerceAtLeast(1)
        val read = try {
            pending.source.read(pending.buffer, limit)
        } catch (_: Exception) {
            0
        }
        if (pending.chunked && read == -1) {
            pending.phase = ChunkPhase.END
            setOutput(if (pending.wroteChunk) CHUNKED_END else EMPTY_CHUNKED_END)
            return true
        }
        if (read !in 1..limit || read > pending.remaining) {
            closeResponse()
            return false
        }
        pending.remaining -= read
        if (pending.chunked) {
            val prefix = if (pending.wroteChunk) "\r\n" else ""
            pending.wroteChunk = true
            pending.chunkLength = read
            pending.phase = ChunkPhase.PAYLOAD
            setOutput("$prefix${read.toString(16)}\r\n".encodeToByteArray())
        } else {
            setOutput(pending.buffer, read)
        }
        return true
    }

    fun closeResponse() {
        val pending = stream ?: return
        stream = null
        pending.source.release()
    }

    private fun queue(response: Response, version: String, keepAlive: Boolean) {
        closeAfterWrite = !keepAlive
        val responseStream = response.stream
        if (responseStream == null) {
            setOutput(response.encode(version, keepAlive))
        } else {
            val length = responseStream.length
            stream = PendingStream(responseStream.source, length ?: limits.responseBodyBytes, chunked = length == null)
            setOutput(response.encodeHead(version, keepAlive, length ?: -1))
        }
    }

    private fun setOutput(bytes: ByteArray, length: Int = bytes.size) {
        output = bytes
        outputOffset = 0
        outputLength = length
    }

    private enum class ChunkPhase { SOURCE, PAYLOAD, END }

    private class PendingStream(val source: ResponseSource, var remaining: Int, val chunked: Boolean) {
        val buffer = ByteArray(minOf(remaining, RESPONSE_CHUNK_BYTES))
        var phase = ChunkPhase.SOURCE
        var wroteChunk = false
        var chunkLength = 0
    }
}

private fun ResponseSource.release() {
    try {
        close()
    } catch (_: Exception) {
        // A source has already been released from HTTP state. Keep serving other clients.
    }
}
