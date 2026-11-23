package dev.perflab.nativeserve

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
            ParsedHead.Incomplete -> return false
            is ParsedHead.Rejected -> {
                headStarted = null
                queue(emptyResponse(head.status), "HTTP/1.1", keepAlive = false)
            }
            is ParsedHead.Complete -> {
                input.consume(head.consumed)
                headStarted = if (input.size == 0) null else now
                val response = routes.respond(head.request)
                if (response.body.size > limits.responseBodyBytes) {
                    queue(emptyResponse(500), head.version, keepAlive = false)
                } else {
                    queue(response, head.version, head.keepAlive)
                }
            }
        }
        return true
    }

    fun wrote(count: Int, now: Long): Boolean {
        val bytes = checkNotNull(output)
        lastActivity = now
        outputOffset += count
        if (outputOffset < bytes.size) return true
        output = null
        return !closeAfterWrite
    }

    private fun queue(response: Response, version: String, keepAlive: Boolean) {
        output = response.encode(version, keepAlive)
        outputOffset = 0
        closeAfterWrite = !keepAlive
    }
}
