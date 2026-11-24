@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.ktserve.http2

import dev.perflab.ktserve.HttpField
import dev.perflab.ktserve.PostBody
import dev.perflab.ktserve.Request
import dev.perflab.ktserve.RequestBodySink
import dev.perflab.ktserve.Response
import dev.perflab.ktserve.ResponseSource
import dev.perflab.ktserve.ResponseStream
import dev.perflab.ktserve.Routes
import dev.perflab.ktserve.ServerLimits
import dev.perflab.ktserve.emptyResponse
import dev.perflab.ktserve.example.parityRoutes
import dev.perflab.ktserve.signals.KTSERVE_INSTALL_SIGNALS
import dev.perflab.ktserve.signals.KTSERVE_READ_STOP
import dev.perflab.ktserve.signals.KTSERVE_RESTORE_SIGNALS
import dev.perflab.ktserve.signals.ktserve_signals
import dev.perflab.ktserve.streamResponse
import dev.perflab.ktserve.text
import dev.perflab.ktserve.transport.ConnectionId
import dev.perflab.ktserve.transport.OperationId
import dev.perflab.ktserve.transport.StreamTransport
import dev.perflab.ktserve.transport.Submission
import dev.perflab.ktserve.transport.TransportCompletions
import dev.perflab.ktserve.transport.TransportFailure
import dev.perflab.ktserve.transport.openEpollTransport
import kotlinx.cinterop.memScoped
import kotlin.time.TimeSource

private const val MAX_STREAMS = 8
private const val SESSION_BUFFER_BYTES = 16 * 1024

fun h2cMain(args: Array<String>) {
    require(args.size <= 2 && (args.size < 2 || args[1] == "--tcp-nodelay")) {
        "Usage: ktserve-h2c [PORT [--tcp-nodelay]]"
    }
    val port = args.firstOrNull()?.toInt() ?: 8080
    check(ktserve_signals(KTSERVE_INSTALL_SIGNALS) == 0) { "Could not install signal handlers" }
    try {
        serveH2cWithSocketOptions(
            port = port,
            host = "127.0.0.1",
            tcpNoDelay = args.size == 2,
            limits = ServerLimits(responseBodyBytes = 128 * 1024),
            stopRequested = { ktserve_signals(KTSERVE_READ_STOP) != 0 },
        ) {
            parityRoutes()
            streamingFixtures()
        }
    } finally {
        ktserve_signals(KTSERVE_RESTORE_SIGNALS)
    }
}

fun serveH2c(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = serveH2cWithSocketOptions(port, host, limits, stopRequested, tcpNoDelay = false, configure = configure)

private fun serveH2cWithSocketOptions(
    port: Int,
    host: String,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
    tcpNoDelay: Boolean,
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val routes = Routes().apply(configure)
    require(routes.applicationHandler == null) { "The h2c experiment does not support a standalone application handler" }
    val transport = openEpollTransport(host, port, limits.connections, tcpNoDelay)
    try {
        println("ktServe backend=epoll protocol=h2c tcp_nodelay=${if (tcpNoDelay) "on" else "default"}")
        H2cServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}

private fun Routes.streamingFixtures() {
    postStreaming("/upload") {
        object : RequestBodySink {
            private var received = 0
            override fun accept(buffer: ByteArray, length: Int) { received += length }
            override fun complete(): Response = text(received.toString())
        }
    }
    get("/stream") {
        streamResponse("application/octet-stream", object : ResponseSource {
            private var remaining = 80 * 1024

            override fun read(buffer: ByteArray, limit: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(remaining, limit)
                buffer.fill('x'.code.toByte(), toIndex = count)
                remaining -= count
                return count
            }
        })
    }
}

private class H2cServer(
    private val transport: StreamTransport,
    private val routes: Routes,
    private val limits: ServerLimits,
) : TransportCompletions {
    private val connections = mutableMapOf<ConnectionId, H2cConnection>()
    private val clock = TimeSource.Monotonic.markNow()
    private var nextOperation = 1L
    private var drainDeadline: Long? = null

    fun run(stopRequested: () -> Boolean) {
        try {
            acceptNext()
            var sweepAt = 0L
            while (true) {
                val now = now()
                if (drainDeadline == null && stopRequested()) {
                    drainDeadline = now + limits.drainMillis
                    transport.closeListener()
                    for ((id, client) in connections.toList()) {
                        withClient(id, client) { client.session.beginDrain() }
                    }
                }
                val deadline = drainDeadline
                if (deadline != null && (connections.isEmpty() || now >= deadline)) break
                transport.poll(if (deadline == null) 250 else minOf(250, deadline - now).toInt(), this)
                val afterPoll = now()
                if (afterPoll >= sweepAt) {
                    val expired = connections.filterValues { afterPoll - it.lastActive >= limits.idleMillis }.keys
                    expired.forEach(::disconnect)
                    for ((id, client) in connections.toList()) {
                        try {
                            if (client.expireBodies(afterPoll)) progress(id, client)
                        } catch (_: Exception) {
                            disconnect(id)
                        }
                    }
                    sweepAt = afterPoll + 250
                }
            }
        } finally {
            connections.values.forEach { it.close() }
            connections.clear()
        }
    }

    override fun accepted(operation: OperationId, connection: ConnectionId) {
        if (drainDeadline != null) {
            transport.closeConnection(connection)
            return
        }
        val client = H2cConnection(routes, limits, now())
        connections[connection] = client
        withClient(connection, client) {}
        acceptNext()
    }

    override fun read(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        val client = connections[connection] ?: return
        client.reading = false
        if (byteCount == 0) {
            disconnect(connection)
            return
        }
        client.lastActive = now()
        withClient(connection, client) { client.session.receive(client.input, 0, byteCount) }
    }

    override fun wrote(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        val client = connections[connection] ?: return
        client.writing = false
        client.lastActive = now()
        client.outputOffset += byteCount
        withClient(connection, client) {}
    }

    override fun failed(connection: ConnectionId?, operation: OperationId, failure: TransportFailure) {
        if (connection != null) disconnect(connection)
        else if (drainDeadline == null) error("Listener operation failed ($failure)")
    }

    private inline fun withClient(id: ConnectionId, client: H2cConnection, action: () -> Unit) {
        try {
            action()
            progress(id, client)
        } catch (_: Exception) {
            disconnect(id)
        }
    }

    private fun progress(id: ConnectionId, client: H2cConnection) {
        if (!client.writing) {
            if (client.outputOffset == client.outputLength) {
                client.outputOffset = 0
                client.outputLength = client.session.output(client.output)
            }
            if (client.outputLength > 0) {
                check(transport.write(id, operation(), client.output, client.outputOffset,
                    client.outputLength - client.outputOffset) == Submission.ACCEPTED)
                client.writing = true
            } else if (drainDeadline != null && client.noOpenStreams()) {
                disconnect(id)
                return
            }
        }
        if (!client.reading) {
            check(transport.read(id, operation(), client.input, 0, client.input.size) == Submission.ACCEPTED)
            client.reading = true
        }
    }

    private fun acceptNext() {
        check(transport.accept(operation()) == Submission.ACCEPTED)
    }

    private fun disconnect(id: ConnectionId) {
        connections.remove(id)?.close()
        transport.closeConnection(id)
    }

    private fun operation() = OperationId(nextOperation++)

    private fun now() = clock.elapsedNow().inWholeMilliseconds
}

private class H2cConnection(private val routes: Routes, private val limits: ServerLimits, var lastActive: Long) : SessionEvents {
    val input = ByteArray(SESSION_BUFFER_BYTES)
    val output = ByteArray(SESSION_BUFFER_BYTES)
    val session = Http2Session(server = true, this)
    var outputOffset = 0
    var outputLength = 0
    var reading = false
    var writing = false
    private val streams = mutableMapOf<Int, RequestHead>()

    fun noOpenStreams() = streams.isEmpty()

    override fun header(stream: Int, name: String, value: String): Boolean {
        val head = streams[stream] ?: run {
            if (streams.size >= MAX_STREAMS) return false
            RequestHead(lastActive).also { streams[stream] = it }
        }
        head.headerBytes += name.encodeToByteArray().size + value.encodeToByteArray().size + 32
        if (head.headerBytes > limits.headerBytes) return false
        if (head.request != null) return true
        if (name == ":method") head.method = value
        if (name == ":path") head.target = value
        if (name == "content-length") head.length = value.toIntOrNull()?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        if (!name.startsWith(':')) head.fields.add(HttpField(name, value))
        return true
    }

    override fun headersEnd(stream: Int) {
        val head = streams[stream] ?: return
        if (head.request != null || head.responded) return
        val target = head.target
        val method = head.method
        if (method == null || target == null || !target.startsWith('/') || target.any { it !in '!'..'~' || it == '#' }) {
            respond(stream, head, emptyResponse(400))
            return
        }
        val query = target.indexOf('?')
        val path = if (query < 0) target else target.substring(0, query)
        val request = Request(
            method, path, if (query < 0) null else target.substring(query + 1),
            bodyLength = head.length, decodedFields = head.fields.toList(), version = "HTTP/2",
        )
        head.request = request
        if (method != "POST") return
        if (head.length?.let { it > limits.requestBodyBytes } == true) {
            respond(stream, head, emptyResponse(413))
            return
        }
        when (val body = routes.openPost(request)) {
            is PostBody.Open -> head.sink = body.sink
            is PostBody.Rejected -> respond(stream, head, body.response)
        }
    }

    override fun data(stream: Int, bytes: ByteArray) {
        val head = streams[stream] ?: return
        if (bytes.isEmpty()) return
        val sink = head.sink
        val accepted = if (sink != null && bytes.size <= limits.requestBodyBytes - head.bodyBytes) {
            try {
                sink.accept(bytes, bytes.size)
                head.bodyBytes += bytes.size
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        session.addCredit(stream, bytes.size)
        if (!accepted && !head.responded) abort(stream, head)
    }

    override fun end(stream: Int) {
        val head = streams[stream] ?: return
        if (head.responded) return
        val request = head.request ?: return
        val declared = head.length
        if (head.sink != null && declared != null && declared != head.bodyBytes) {
            head.closeSink()
            respond(stream, head, emptyResponse(400))
            return
        }
        val response = try {
            head.sink?.complete() ?: routes.respond(request)
        } catch (_: Exception) {
            emptyResponse(500)
        }
        head.closeSink()
        respond(stream, head, response)
    }

    private fun respond(stream: Int, head: RequestHead, response: Response) {
        head.responded = true
        val source = response.stream
        if (source is ResponseStream.Produce) {
            source.discard()
            session.respond(stream, 501, ByteArray(0), contentType = null)
            return
        }
        if ((source?.length ?: response.body.size) > limits.responseBodyBytes ||
            response.applicationHeaderBytes > limits.responseHeaderBytes
        ) {
            source?.discard()
            session.respond(stream, 500, ByteArray(0), contentType = null)
            return
        }
        val allowed = response.allowedMethods
        val fields = if (response.status == 405 && allowed != null) {
            response.fields + HttpField("allow", allowed)
        } else response.fields
        if (source is ResponseStream.Pull) {
            session.respond(
                stream, response.status, response.contentType, source.source, source.length, limits.responseBodyBytes, fields,
            )
        } else {
            val body = if (response.status == 204 || response.status == 304) ByteArray(0) else response.body
            session.respond(stream, response.status, body, response.contentType, fields)
        }
    }

    override fun closed(stream: Int, code: UInt) {
        streams.remove(stream)?.closeSink()
    }

    fun expireBodies(now: Long): Boolean {
        val expired = streams.filterValues { it.sink != null && now - it.started >= limits.requestBodyMillis }
        for ((stream, head) in expired) abort(stream, head)
        return expired.isNotEmpty()
    }

    private fun abort(stream: Int, head: RequestHead) {
        head.responded = true
        head.closeSink()
        session.cancel(stream)
    }

    fun close() {
        streams.values.forEach(RequestHead::closeSink)
        session.close()
        streams.clear()
    }

    private class RequestHead(val started: Long) {
        val fields = mutableListOf<HttpField>()
        var method: String? = null
        var target: String? = null
        var length: Int? = null
        var request: Request? = null
        var sink: RequestBodySink? = null
        var headerBytes = 0
        var bodyBytes = 0
        var responded = false

        fun closeSink() {
            val current = sink ?: return
            sink = null
            try {
                current.close()
            } catch (_: Exception) {
                // The sink is already detached from this stream.
            }
        }
    }
}
