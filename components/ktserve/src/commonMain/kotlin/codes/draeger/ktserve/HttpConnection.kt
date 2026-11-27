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
        require(connections > 0) { "connections must be at least 1" }
        require(headerBytes >= 64) { "headerBytes must be at least 64" }
        require(responseBodyBytes > 0) { "responseBodyBytes must be at least 1" }
        require(idleMillis > 0) { "idleMillis must be at least 1" }
        require(requestHeadMillis > 0) { "requestHeadMillis must be at least 1" }
        require(drainMillis > 0) { "drainMillis must be at least 1" }
        require(requestBodyBytes >= 0) { "requestBodyBytes must be at least 0" }
        require(requestBodyMillis > 0) { "requestBodyMillis must be at least 1" }
        require(handlerMillis > 0) { "handlerMillis must be at least 1" }
        require(responseHeaderBytes > 0) { "responseHeaderBytes must be at least 1" }
        require(responseMillis > 0) { "responseMillis must be at least 1" }
        require(blockingHandlers > 0) { "blockingHandlers must be at least 1" }
    }
}

internal enum class ExchangeExpiry { IDLE, REQUEST_HEAD, REQUEST_BODY, HANDLER, RESPONSE }

internal data class IdleHttpConnection(val lastActivity: Long, val input: RequestBuffer)

internal class HttpConnection(
    connectedAt: Long,
    private val limits: ServerLimits,
    private val dispatcher: (() -> CoroutineScope)? = null,
    private val countRequest: (() -> Unit)? = null,
    private val signalApplicationProgress: (() -> Unit)? = null,
    private val observation: ExchangeObservation? = null,
    val input: RequestBuffer = RequestBuffer(limits.headerBytes),
) {
    var output: ByteArray? = null
        private set
    var outputOffset = 0
        private set
    var outputLength = 0
        private set
    private var incoming: IncomingBody? = null
    private var stream: PendingStream? = null
    private var handler: PendingHandler? = null
    private var extensionExchange: ExtensionExchange? = null
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

    fun activeSince(since: Long): Boolean = lastActivity >= since

    fun idleSnapshot(since: Long): IdleHttpConnection? {
        if (lastActivity < since || input.size != 0 || headStarted != null || output != null ||
            incoming != null || stream != null || handler != null || writePending || monitorPending ||
            bodyBlocked || draining || closeAfterWrite
        ) return null
        return IdleHttpConnection(lastActivity, input)
    }

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
        if (input.size == 0 && incoming == null && !awaitingHandler && output == null && stream == null) {
            headStarted = now
            observation?.begin()
        }
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
        val hasLegacyDispatch = routes.hasLegacyRequestDispatch
        val hasRequestRoutes = hasLegacyDispatch && routes.hasRequestRoutes
        when (val head = input.parse(allowAnyRequestBody = application != null || hasRequestRoutes)) {
            ParsedHead.Incomplete -> {
                // Pipelined bytes start their deadline when the previous response lets us parse them.
                if (input.size > 0 && headStarted == null) {
                    headStarted = now
                    observation?.begin()
                }
                return false
            }
            is ParsedHead.Rejected -> {
                observation?.begin()
                headStarted = null
                queue(emptyResponse(head.status), "HTTP/1.1", keepAlive = false, now)
            }
            is ParsedHead.Complete -> {
                observation?.identify(head.request.method)
                countRequest?.invoke()
                input.consume(head.consumed)
                headStarted = null
                routes.extensionHooks?.let { hooks ->
                    val exchange = ExtensionExchange(hooks, head.request)
                    extensionExchange = exchange
                    val response = exchange.beforeRequest()
                    if (response != null) {
                        val keepAlive = head.keepAlive && head.request.bodyLength == 0
                        queue(response, head.version, keepAlive, now, head.request.method == "HEAD")
                        return true
                    }
                }
                if (hasLegacyDispatch) {
                    return prepareExtendedResponse(routes, head, now, start, startBlocking)
                }
                if (application != null) {
                    extensionExchange?.beforeDelegatedHandler()
                    return prepareApplication(head, now, checkNotNull(start), application)
                }
                return prepareStandalone(routes, head, now, head.keepAlive, start, startBlocking)
            }
        }
        return true
    }

    private fun prepareExtendedResponse(
        routes: Routes,
        head: ParsedHead.Complete,
        now: Long,
        start: ((suspend () -> Response) -> SuspendedGet)?,
        startBlocking: ((() -> Response) -> SuspendedGet?)?,
    ): Boolean {
        val request = head.request
        val headOnly = request.method == "HEAD"
        val keepAlive = head.keepAlive && request.bodyLength == 0
        val denial = try {
            routes.requestPolicy?.invoke(request)
        } catch (_: Exception) {
            emptyResponse(500)
        }
        if (denial != null) {
            queue(denial, head.version, keepAlive, now, headOnly)
            return true
        }
        val application = routes.applicationHandler
        if (application != null) {
            extensionExchange?.beforeDelegatedHandler()
            return prepareApplication(head, now, checkNotNull(start), application)
        }
        routes.dispatchRequestRoute(request) { handler, matched, method, template ->
            val response = extensionExchange?.beforeRoute(matched, method, template)
            if (response == null) {
                prepareApplication(head, now, checkNotNull(start), handler, matched)
            } else {
                queue(response, head.version, keepAlive, now, headOnly)
                true
            }
        }?.let { return it }
        if ((request.method == "GET" || headOnly) && request.bodyLength != 0) {
            queue(emptyResponse(413), head.version, keepAlive = false, now, headOnly)
            return true
        }
        return prepareStandalone(routes, head, now, keepAlive, start, startBlocking)
    }

    private fun prepareStandalone(
        routes: Routes,
        head: ParsedHead.Complete,
        now: Long,
        keepAlive: Boolean,
        start: ((suspend () -> Response) -> SuspendedGet)?,
        startBlocking: ((() -> Response) -> SuspendedGet?)?,
    ): Boolean {
        val request = head.request
        if (request.method == "POST") return preparePost(routes, head, now)
        val headOnly = when (request.method) {
            "GET" -> false
            "HEAD" -> true
            else -> {
                queue(routes.http1MethodError(request), head.version, keepAlive, now)
                return true
            }
        }
        val response = try {
            routes.dispatchMatchedGet(request) { handler, matched, template ->
                val rejection = extensionExchange?.beforeRoute(matched, "GET", template)
                if (rejection != null) return@dispatchMatchedGet rejection
                when (handler) {
                    is GetHandler.Immediate -> handler.run(matched)
                    is GetHandler.Suspending -> {
                        if (start == null) emptyResponse(501)
                        else {
                            handlerStarted(start { handler.run(matched) }, head, now, keepAlive, headOnly)
                            return false
                        }
                    }
                    is GetHandler.Blocking -> {
                        if (startBlocking == null) emptyResponse(501)
                        else {
                            val call = startBlocking { handler.run(matched) }
                            if (call == null) emptyResponse(503)
                            else {
                                handlerStarted(call, head, now, keepAlive, headOnly)
                                return false
                            }
                        }
                    }
                }
            } ?: routes.http1MethodError(request)
        } catch (_: Exception) {
            emptyResponse(500)
        }
        queue(response, head.version, keepAlive, now, headOnly)
        return true
    }

    private fun handlerStarted(
        call: SuspendedGet,
        head: ParsedHead.Complete,
        now: Long,
        keepAlive: Boolean,
        headOnly: Boolean,
    ) {
        handler = PendingHandler(call, head.version, keepAlive, now, headOnly)
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
        val body = IncomingBody.Content(decoder(length), head.version, head.keepAlive, now, content)
        incoming = body
        return consumeBody(body, now)
    }

    private fun preparePost(routes: Routes, head: ParsedHead.Complete, now: Long): Boolean {
        val length = head.request.bodyLength
        if (length != null && length > limits.requestBodyBytes) {
            queue(emptyResponse(413), head.version, keepAlive = false, now)
            return true
        }
        return when (val post = routes.openPost(head.request, http1Methods = true, exchange = extensionExchange)) {
            is PostBody.Rejected -> {
                queue(post.response, head.version, head.keepAlive && length == 0, now)
                true
            }
            is PostBody.Open -> {
                val body = IncomingBody.Sink(decoder(length), head.version, head.keepAlive, now, post.sink)
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
            when (val progress = body.decoder.consume(input, body)) {
                BodyProgress.INCOMPLETE -> return false
                BodyProgress.BLOCKED -> {
                    bodyBlocked = true
                    return false
                }
                BodyProgress.COMPLETE -> when (body) {
                    is IncomingBody.Content -> {
                        incoming = null
                        body.content.finish()
                        return false
                    }
                    is IncomingBody.Sink -> {
                        val response = body.sink.complete()
                        closeRequest()
                        queue(response, body.version, body.keepAlive, now)
                    }
                }
                BodyProgress.INVALID, BodyProgress.OVERSIZE, BodyProgress.METADATA_LIMIT -> {
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
        if (responseExpired(now)) {
            endExchange(ExchangeOutcome.RESPONSE_TIMEOUT)
            return false
        }
        lastActivity = now
        outputOffset += count
        if (outputOffset < outputLength) return true
        output = null
        val pending = stream
        if (pending == null) {
            responseDeadline = Long.MAX_VALUE
            endExchange(ExchangeOutcome.COMPLETED)
            return !closeAfterWrite
        }
        if (pending.phase == ChunkPhase.END ||
            !pending.chunked && pending.remaining == 0 && pending.source !is AsyncResponseSource
        ) {
            closeResponse()
            endExchange(ExchangeOutcome.COMPLETED)
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
            endExchange(ExchangeOutcome.COMPLETED)
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

    fun resumeProducer(now: Long): Boolean {
        if (responseExpired(now)) {
            endExchange(ExchangeOutcome.RESPONSE_TIMEOUT)
            return false
        }
        return pull(checkNotNull(stream))
    }

    fun closeResponse() {
        responseDeadline = Long.MAX_VALUE
        val pending = stream ?: return
        stream = null
        pending.source.release()
    }

    fun close(outcome: ExchangeOutcome = ExchangeOutcome.DISCONNECTED) {
        cancelHandler()
        closeRequest()
        closeResponse()
        endExchange(outcome)
    }

    private fun endExchange(outcome: ExchangeOutcome) {
        observation?.end(outcome)
        val exchange = extensionExchange ?: return
        extensionExchange = null
        exchange.complete(outcome)
    }

    private fun closeRequest() {
        val body = incoming ?: return
        incoming = null
        bodyBlocked = false
        when (body) {
            is IncomingBody.Content -> body.content.cancel()
            is IncomingBody.Sink -> body.sink.release()
        }
    }

    private fun cancelHandler() {
        handler?.call?.cancel()
        handler = null
    }

    private fun queue(selected: Response, version: String, keepAlive: Boolean, now: Long, headOnly: Boolean = false) {
        // Keep the same deadline across the header and every buffered or streamed write.
        responseDeadline = now + minOf(limits.responseMillis, Long.MAX_VALUE - now)
        val exchange = extensionExchange
        val response = try {
            exchange?.response(selected) ?: selected
        } catch (_: Exception) {
            selected.stream?.discard()
            emptyResponse(500)
        }
        val stream = response.stream
        val chunked = stream != null && stream.length == null
        if ((stream?.length ?: response.body.size) > limits.responseBodyBytes ||
            response.applicationHeaderBytes > limits.responseHeaderBytes || chunked && version != "HTTP/1.1" && !headOnly
        ) {
            stream?.discard()
            closeAfterWrite = true
            observation?.response(500)
            exchange?.status = 500
            setOutput(emptyResponse(500).encode(version, keepAlive = false))
            return
        }
        val persistent = keepAlive && !draining
        closeAfterWrite = !persistent
        observation?.response(response.status)
        exchange?.status = response.status
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
            val preparedHead = if (persistent && version == "HTTP/1.1" && stream is ResponseStream.Pull) {
                stream.keepAliveHead
            } else null
            setOutput(preparedHead ?: response.encodeHead(version, persistent, length ?: CHUNKED_RESPONSE_LENGTH))
        }
    }

    private sealed class IncomingBody(
        val decoder: RequestBodyDecoder,
        val version: String,
        val keepAlive: Boolean,
        val started: Long,
    ) : BodyInput {
        class Content(
            decoder: RequestBodyDecoder,
            version: String,
            keepAlive: Boolean,
            started: Long,
            val content: RequestContent,
        ) : IncomingBody(decoder, version, keepAlive, started) {
            override fun offer(bytes: ByteArray, length: Int): Boolean = content.offer(bytes, length)
        }

        class Sink(
            decoder: RequestBodyDecoder,
            version: String,
            keepAlive: Boolean,
            started: Long,
            val sink: RequestBodySink,
        ) : IncomingBody(decoder, version, keepAlive, started) {
            override fun offer(bytes: ByteArray, length: Int): Boolean {
                sink.accept(bytes, length)
                return true
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
