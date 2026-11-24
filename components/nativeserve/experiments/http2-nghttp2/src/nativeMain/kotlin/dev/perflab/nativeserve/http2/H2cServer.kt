@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.http2

import dev.perflab.nativeserve.Request
import dev.perflab.nativeserve.Routes
import dev.perflab.nativeserve.ServerLimits
import dev.perflab.nativeserve.emptyResponse
import dev.perflab.nativeserve.example.parityRoutes
import dev.perflab.nativeserve.signals.REACTOR_INSTALL_SIGNALS
import dev.perflab.nativeserve.signals.REACTOR_READ_STOP
import dev.perflab.nativeserve.signals.REACTOR_RESTORE_SIGNALS
import dev.perflab.nativeserve.signals.reactor_signals
import dev.perflab.nativeserve.transport.ConnectionId
import dev.perflab.nativeserve.transport.OperationId
import dev.perflab.nativeserve.transport.StreamTransport
import dev.perflab.nativeserve.transport.Submission
import dev.perflab.nativeserve.transport.TransportCompletions
import dev.perflab.nativeserve.transport.TransportFailure
import dev.perflab.nativeserve.transport.openPlatformTransport
import kotlinx.cinterop.memScoped
import kotlin.time.TimeSource

private const val MAX_STREAMS = 8
private const val SESSION_BUFFER_BYTES = 16 * 1024

fun h2cMain(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    check(reactor_signals(REACTOR_INSTALL_SIGNALS) == 0) { "Could not install signal handlers" }
    try {
        memScoped {
            val limits = ServerLimits()
            val transport = openPlatformTransport("127.0.0.1", port, limits.connections)
            try {
                println("NativeServe backend=epoll protocol=h2c")
                H2cServer(transport, Routes().apply { parityRoutes() }, limits).run()
            } finally {
                transport.close()
            }
        }
    } finally {
        reactor_signals(REACTOR_RESTORE_SIGNALS)
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

    fun run() {
        try {
            acceptNext()
            var sweepAt = 0L
            while (true) {
                val now = now()
                if (drainDeadline == null && reactor_signals(REACTOR_READ_STOP) != 0) {
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
                    sweepAt = afterPoll + 250
                }
            }
        } finally {
            connections.values.forEach { it.session.close() }
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
        connections.remove(id)?.session?.close()
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
            RequestHead().also { streams[stream] = it }
        }
        head.headerBytes += name.encodeToByteArray().size + value.encodeToByteArray().size + 32
        if (head.headerBytes > limits.headerBytes) return false
        if (name == ":method") head.method = value
        if (name == ":path") head.target = value
        return true
    }

    override fun data(stream: Int, bytes: ByteArray) {
        val head = streams[stream] ?: return
        head.hasBody = true
        // The preview rejects request payloads. Reset promptly and return consumed connection credit.
        if (bytes.isNotEmpty()) session.addCredit(stream, bytes.size)
        session.cancel(stream)
    }

    override fun end(stream: Int) {
        val head = streams[stream] ?: return
        if (head.hasBody) return
        val target = head.target
        val method = head.method
        val response = if (method != null && target != null && target.startsWith('/') && target.all { it in '!'..'~' && it != '#' }) {
            val query = target.indexOf('?')
            val path = if (query < 0) target else target.substring(0, query)
            routes.respond(Request(method, path, if (query < 0) null else target.substring(query + 1)))
        } else {
            emptyResponse(400)
        }

        val source = response.stream?.source
        if (source != null || response.body.size > limits.responseBodyBytes) {
            source?.close()
            session.respond(stream, 500, ByteArray(0), contentType = null)
        } else {
            val body = if (response.status == 204 || response.status == 304) ByteArray(0) else response.body
            session.respond(stream, response.status, body, response.contentType)
        }
    }

    override fun closed(stream: Int, code: UInt) {
        streams.remove(stream)
    }

    private class RequestHead {
        var method: String? = null
        var target: String? = null
        var headerBytes = 0
        var hasBody = false
    }
}
