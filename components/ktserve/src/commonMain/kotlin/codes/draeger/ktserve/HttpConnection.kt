package codes.draeger.ktserve

import kotlinx.coroutines.CoroutineScope

private val EMPTY_CHUNKED_END = "0\r\n\r\n".encodeToByteArray()
private val CHUNKED_END = "\r\n0\r\n\r\n".encodeToByteArray()

class ServerLimits(
    val connections: Int = 1024,
    val headerBytes: Int = 16 * 1024,
    val responseBodyBytes: Int = 64 * 1024,
    val idleMillis: Long = 30_000,
    val requestHeadMillis: Long = 5_000,
    val drainMillis: Long = 2_000,
    val requestBodyBytes: Int = 64 * 1024,
    val requestBodyMillis: Long = 30_000,
    val handlerMillis: Long = 30_000,
    val responseHeaderBytes: Int = 16 * 1024,
    val responseMillis: Long = 30_000,
    val blockingHandlers: Int = 8,
) {
    init {
        require(connections > 0 && headerBytes >= 64 && responseBodyBytes > 0)
        require(idleMillis > 0 && requestHeadMillis > 0 && drainMillis > 0)
        require(requestBodyBytes >= 0 && requestBodyMillis > 0 && handlerMillis > 0 && responseHeaderBytes > 0)
        require(responseMillis > 0 && blockingHandlers > 0)
    }
}

internal enum class ExchangeExpiry { IDLE, REQUEST_HEAD, REQUEST_BODY, HANDLER, RESPONSE }

internal class HttpConnection(
    connectedAt: Long,
    private val limits: ServerLimits,
    private val dispatcher: (() -> CoroutineScope)? = null,
    private val countRequest: (() -> Unit)? = null,
    private val signalApplicationProgress: (() -> Unit)? = null,
) {
    val input = RequestBuffer(limits.headerBytes)
    var output: ByteArray? = null
        private set
    var outputOffset = 0
        private set
    var outputLength = 0
        private set
    private var incoming: IncomingBody? = null
    private var stream: PendingStream? = null
    private var handler: PendingHandler? = null
    val awaitingHandler: Boolean get() = handler != null
    val awaitingProducer: Boolean get() = stream?.waiting == true
    var bodyBlocked = false
        private set
    var readPending = false
    var writePending = false
    private var monitor: ByteArray? = null
    private var monitorPending = false
    private var draining = false
    private var closeAfterWrite = false
    private var lastActivity = connectedAt
    private var headStarted: Long? = null
    private var responseDeadline = Long.MAX_VALUE

    fun expired(now: Long): Boolean = expiry(now) != null

    fun expiry(now: Long): ExchangeExpiry? = when {
        responseExpired(now) -> ExchangeExpiry.RESPONSE
        headStarted?.let { now - it >= limits.requestHeadMillis } == true -> ExchangeExpiry.REQUEST_HEAD
        incoming?.let { now - it.started >= limits.requestBodyMillis } == true -> ExchangeExpiry.REQUEST_BODY
        handler?.let { now - it.started >= limits.handlerMillis } == true -> ExchangeExpiry.HANDLER
        now - lastActivity >= limits.idleMillis -> ExchangeExpiry.IDLE
        else -> null
    }

    fun responseExpired(now: Long): Boolean = now >= responseDeadline

    fun beginDrain(): Boolean {
        draining = true
        closeAfterWrite = true
        return output != null || awaitingHandler || awaitingProducer
    }

    fun received(count: Int, now: Long) {
        lastActivity = now
        readPending = false
        if (monitorPending) {
            monitorPending = false
            checkNotNull(monitor).copyInto(input.bytes, input.size, endIndex = count)
        }
        if (input.size == 0 && incoming == null && !awaitingHandler && output == null) headStarted = now
        input.append(count)
    }

    // While the handler runs, monitor EOF without leasing a range used by the parser.
    fun monitorBuffer(): ByteArray {
        monitorPending = true
        return monitor ?: ByteArray(minOf(4096, limits.headerBytes)).also { monitor = it }
    }

    fun finishHandler(now: Long): Boolean {
        val pending = handler ?: return false
        val response = pending.call.takeResponse() ?: return false
        handler = null
        if (incoming != null) {
            closeRequest()
            draining = true
        }
        lastActivity = now
        queue(response, pending.version, pending.keepAlive, now, pending.headOnly)
        return true
    }

    fun prepareResponse(
        routes: Routes,
        now: Long,
        start: ((suspend () -> Response) -> SuspendedGet)? = null,
        startBlocking: ((() -> Response) -> SuspendedGet?)? = null,
    ): Boolean {
        if (output != null) return true
        incoming?.let { return consumeBody(it, now) }
        if (awaitingHandler || stream != null) return false
        val application = routes.applicationHandler
        val hasRequestRoutes = routes.hasRequestRoutes
        when (val head = input.parse(allowAnyRequestBody = application != null || hasRequestRoutes)) {
            ParsedHead.Incomplete -> {
                // Pipelined bytes start their deadline when the previous response lets us parse them.
                if (input.size > 0 && headStarted == null) headStarted = now
                return false
            }
            is ParsedHead.Rejected -> {
                headStarted = null
                queue(emptyResponse(head.status), "HTTP/1.1", keepAlive = false, now)
            }
            is ParsedHead.Complete -> {
                countRequest?.invoke()
                input.consume(head.consumed)
                headStarted = null
                val request = head.request
                val headOnly = request.method == "HEAD"
                val denial = routes.requestPolicy?.let { policy ->
                    try {
                        policy(request)
                    } catch (_: Exception) {
                        emptyResponse(500)
                    }
                }
                if (denial != null) {
                    queue(denial, head.version, head.keepAlive && request.bodyLength == 0, now, headOnly)
                    return true
                }
                if (application != null) return prepareApplication(head, now, checkNotNull(start), application)
                if (hasRequestRoutes) {
                    routes.dispatchRequestRoute(request) { handler, matched ->
                        prepareApplication(head, now, checkNotNull(start), handler, matched)
                    }?.let { return it }
                }
                if (request.method == "POST") return preparePost(routes, head, now)
                if (request.bodyLength != 0) {
                    queue(emptyResponse(413), head.version, keepAlive = false, now, headOnly)
                    return true
                }
                val launch: ((suspend () -> Response) -> Unit)? = start?.let {
                    { run -> handler = PendingHandler(it(run), head.version, head.keepAlive, now, headOnly) }
                }
                val launchBlocking: ((() -> Response) -> Boolean)? = startBlocking?.let {
                    { run ->
                        val call = it(run)
                        if (call == null) {
                            false
                        } else {
                            handler = PendingHandler(call, head.version, head.keepAlive, now, headOnly)
                            true
                        }
                    }
                }
                val response = routes.respond(request, launch, launchBlocking) ?: return false
                queue(response, head.version, head.keepAlive, now, headOnly)
            }
        }
        return true
    }

    private fun prepareApplication(
        head: ParsedHead.Complete,
        now: Long,
        start: (suspend () -> Response) -> SuspendedGet,
        application: RequestHandler,
        request: Request = head.request,
    ): Boolean {
        val length = request.bodyLength
        val headOnly = request.method == "HEAD"
        if (length != null && length > limits.requestBodyBytes) {
            queue(emptyResponse(413), head.version, keepAlive = false, now, headOnly)
            return true
        }
        val content = RequestContent(signalApplicationProgress)
        handler = PendingHandler(
            start { application(request, content) }, head.version, head.keepAlive, now, headOnly,
        )
        if (length == 0) {
            content.finish()
            return false
        }
        val body = IncomingBody(decoder(length), head.version, head.keepAlive, now, content = content)
        incoming = body
        return consumeBody(body, now)
    }

    private fun preparePost(routes: Routes, head: ParsedHead.Complete, now: Long): Boolean {
        val length = head.request.bodyLength
        if (length != null && length > limits.requestBodyBytes) {
            queue(emptyResponse(413), head.version, keepAlive = false, now)
            return true
        }
        return when (val post = routes.openPost(head.request)) {
            is PostBody.Rejected -> {
                queue(post.response, head.version, head.keepAlive && length == 0, now)
                true
            }
            is PostBody.Open -> {
                val body = IncomingBody(decoder(length), head.version, head.keepAlive, now, sink = post.sink)
                incoming = body
                consumeBody(body, now)
            }
        }
    }

    private fun decoder(length: Int?): RequestBodyDecoder =
        if (length == null) ChunkedBody(limits.requestBodyBytes, limits.headerBytes) else FixedLengthBody(length)

    private fun consumeBody(body: IncomingBody, now: Long): Boolean {
        bodyBlocked = false
        try {
            when (val progress = body.decoder.consume(input, body.input)) {
                BodyProgress.INCOMPLETE -> return false
                BodyProgress.BLOCKED -> {
                    bodyBlocked = true
                    return false
                }
                BodyProgress.COMPLETE -> {
                    val content = body.content
                    if (content != null) {
                        incoming = null
                        content.finish()
                        return false
                    }
                    val response = checkNotNull(body.sink).complete()
                    closeRequest()
                    queue(response, body.version, body.keepAlive, now)
                }
                else -> {
                    closeRequest()
                    cancelHandler()
                    queue(emptyResponse(progress.status), body.version, keepAlive = false, now)
                }
            }
        } catch (_: Exception) {
            closeRequest()
            cancelHandler()
            queue(emptyResponse(500), body.version, keepAlive = false, now)
        }
        return true
    }

    fun wrote(count: Int, now: Long): Boolean {
        checkNotNull(output)
        if (responseExpired(now)) return false
        lastActivity = now
        outputOffset += count
        if (outputOffset < outputLength) return true
        output = null
        val pending = stream
        if (pending == null) {
            responseDeadline = Long.MAX_VALUE
            return !closeAfterWrite
        }
        if (pending.phase == ChunkPhase.END ||
            !pending.chunked && pending.remaining == 0 && pending.source !is AsyncResponseSource
        ) {
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
        pending.waiting = read == SOURCE_WAITING && pending.source is AsyncResponseSource
        if (pending.waiting) return true
        if (pending.chunked && read == -1) {
            pending.phase = ChunkPhase.END
            setOutput(if (pending.wroteChunk) CHUNKED_END else EMPTY_CHUNKED_END)
            return true
        }
        if (read == -1 && pending.remaining == 0) {
            closeResponse()
            return !closeAfterWrite
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

    fun resumeProducer(now: Long): Boolean = !responseExpired(now) && pull(checkNotNull(stream))

    fun closeResponse() {
        responseDeadline = Long.MAX_VALUE
        val pending = stream ?: return
        stream = null
        pending.source.release()
    }

    fun close() {
        cancelHandler()
        closeRequest()
        closeResponse()
    }

    private fun closeRequest() {
        val body = incoming ?: return
        incoming = null
        bodyBlocked = false
        body.sink?.release()
        body.content?.cancel()
    }

    private fun cancelHandler() {
        handler?.call?.cancel()
        handler = null
    }

    private fun queue(response: Response, version: String, keepAlive: Boolean, now: Long, headOnly: Boolean = false) {
        // Keep the same deadline across the header and every buffered or streamed write.
        responseDeadline = now + minOf(limits.responseMillis, Long.MAX_VALUE - now)
        val stream = response.stream
        val chunked = stream != null && stream.length == null
        if ((stream?.length ?: response.body.size) > limits.responseBodyBytes ||
            response.applicationHeaderBytes > limits.responseHeaderBytes || chunked && version != "HTTP/1.1" && !headOnly
        ) {
            stream?.discard()
            closeAfterWrite = true
            setOutput(emptyResponse(500).encode(version, keepAlive = false))
            return
        }
        val persistent = keepAlive && !draining
        closeAfterWrite = !persistent
        if (headOnly || stream is ResponseStream.Produce && stream.length == 0) {
            stream?.discard()
            val length = if (chunked) OMIT_RESPONSE_LENGTH else stream?.length ?: response.body.size
            setOutput(response.encodeHead(version, persistent, length))
        } else if (stream == null) {
            setOutput(response.encode(version, persistent))
        } else {
            val length = stream.length
            val source = when (stream) {
                is ResponseStream.Pull -> stream.source
                is ResponseStream.Produce ->
                    AsyncResponseSource(checkNotNull(dispatcher).invoke(), stream, signalApplicationProgress)
            }
            this.stream = PendingStream(source, length ?: limits.responseBodyBytes, chunked)
            setOutput(response.encodeHead(version, persistent, length ?: CHUNKED_RESPONSE_LENGTH))
        }
    }

    private class IncomingBody(
        val decoder: RequestBodyDecoder,
        val version: String,
        val keepAlive: Boolean,
        val started: Long,
        val sink: RequestBodySink? = null,
        val content: RequestContent? = null,
    ) {
        val input = BodyInput { bytes, length ->
            if (content != null) content.offer(bytes, length)
            else {
                checkNotNull(sink).accept(bytes, length)
                true
            }
        }
    }

    private class PendingHandler(
        val call: SuspendedGet,
        val version: String,
        val keepAlive: Boolean,
        val started: Long,
        val headOnly: Boolean = false,
    )

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
        var waiting = false
    }
}

internal fun AutoCloseable.release() {
    try {
        close()
    } catch (_: Exception) {
        // The body has already been released from HTTP state. Keep serving other clients.
    }
}
