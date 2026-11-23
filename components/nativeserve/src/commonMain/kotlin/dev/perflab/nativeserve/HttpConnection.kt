package dev.perflab.nativeserve

private const val RESPONSE_CHUNK_BYTES = 16 * 1024

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
                if ((responseStream?.length ?: response.body.size) > limits.responseBodyBytes) {
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
        if (pending.remaining == 0) {
            closeResponse()
            return !closeAfterWrite
        }
        val limit = minOf(pending.remaining, pending.buffer.size)
        val read = try {
            pending.source.read(pending.buffer, limit)
        } catch (_: Exception) {
            0
        }
        if (read !in 1..limit) {
            closeResponse()
            return false
        }
        pending.remaining -= read
        setOutput(pending.buffer, read)
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
            stream = PendingStream(responseStream.source, responseStream.length)
            setOutput(response.encodeHead(version, keepAlive, responseStream.length))
        }
    }

    private fun setOutput(bytes: ByteArray, length: Int = bytes.size) {
        output = bytes
        outputOffset = 0
        outputLength = length
    }

    private class PendingStream(val source: ResponseSource, var remaining: Int) {
        val buffer = ByteArray(minOf(remaining, RESPONSE_CHUNK_BYTES))
    }
}

private fun ResponseSource.release() {
    try {
        close()
    } catch (_: Exception) {
        // A source has already been released from HTTP state. Keep serving other clients.
    }
}
