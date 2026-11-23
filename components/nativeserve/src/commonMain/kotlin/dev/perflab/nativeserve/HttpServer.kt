package dev.perflab.nativeserve

import dev.perflab.nativeserve.transport.ConnectionId
import dev.perflab.nativeserve.transport.OperationId
import dev.perflab.nativeserve.transport.StreamTransport
import dev.perflab.nativeserve.transport.Submission
import dev.perflab.nativeserve.transport.TransportCompletions
import dev.perflab.nativeserve.transport.TransportFailure
import kotlin.time.TimeSource

internal class HttpServer(
    private val transport: StreamTransport,
    private val routes: Routes,
    private val limits: ServerLimits,
) : TransportCompletions {
    private val clients = mutableMapOf<ConnectionId, HttpConnection>()
    private val clock = TimeSource.Monotonic.markNow()
    private var nextOperation = 1L
    private var drainDeadline: Long? = null
    private var batchTime = -1L

    fun run(stopRequested: () -> Boolean) {
        var nextSweep = 0L
        acceptNext()
        while (true) {
            val beforePoll = now()
            if (drainDeadline == null && stopRequested()) {
                drainDeadline = beforePoll + limits.drainMillis
                transport.closeListener()
                closeMatching { !it.beginDrain() }
            }
            val deadline = drainDeadline
            if (deadline != null && (clients.isEmpty() || beforePoll >= deadline)) break
            val timeout = if (deadline == null) 250 else minOf(250L, deadline - beforePoll).toInt()
            batchTime = -1L
            transport.poll(timeout, this)
            val afterPoll = completionTime()
            if (afterPoll >= nextSweep) {
                closeMatching { it.expired(afterPoll) }
                nextSweep = afterPoll + 250
            }
        }
    }

    override fun accepted(operation: OperationId, connection: ConnectionId) {
        if (drainDeadline != null) {
            transport.closeConnection(connection)
            return
        }
        val time = completionTime()
        val client = HttpConnection(time, limits)
        clients[connection] = client
        advance(connection, client, time)
        acceptNext()
    }

    override fun read(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        val client = clients[connection] ?: return
        if (byteCount == 0) {
            disconnect(connection)
            return
        }
        val time = completionTime()
        client.received(byteCount, time)
        advance(connection, client, time)
    }

    override fun wrote(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        val client = clients[connection] ?: return
        val time = completionTime()
        if (client.wrote(byteCount, time)) advance(connection, client, time) else disconnect(connection)
    }

    override fun failed(connection: ConnectionId?, operation: OperationId, failure: TransportFailure) {
        if (connection != null) {
            disconnect(connection)
        } else if (drainDeadline == null) {
            error("Listener operation failed ($failure)")
        }
    }

    private fun advance(id: ConnectionId, client: HttpConnection, time: Long) {
        val operation = OperationId(nextOperation++)
        val result = if (client.prepareResponse(routes, time)) {
            val output = checkNotNull(client.output)
            transport.write(id, operation, output, client.outputOffset, output.size - client.outputOffset)
        } else {
            val input = client.input
            transport.read(id, operation, input.bytes, input.size, input.bytes.size - input.size)
        }
        if (result != Submission.ACCEPTED) disconnect(id)
    }

    private fun acceptNext() {
        check(transport.accept(OperationId(nextOperation++)) == Submission.ACCEPTED) { "Could not submit accept" }
    }

    private fun closeMatching(shouldClose: (HttpConnection) -> Boolean) {
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val (id, connection) = iterator.next()
            if (shouldClose(connection)) {
                iterator.remove()
                transport.closeConnection(id)
            }
        }
    }

    private fun disconnect(id: ConnectionId) {
        clients.remove(id)
        transport.closeConnection(id)
    }

    private fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    private fun completionTime(): Long {
        // Sample after the wait, once for the readiness batch.
        if (batchTime < 0) batchTime = now()
        return batchTime
    }
}
