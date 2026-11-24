package dev.perflab.nativeserve

import dev.perflab.nativeserve.transport.ConnectionId
import dev.perflab.nativeserve.transport.OperationId
import dev.perflab.nativeserve.transport.StreamTransport
import dev.perflab.nativeserve.transport.Submission
import dev.perflab.nativeserve.transport.TransportCompletions
import dev.perflab.nativeserve.transport.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.TimeSource

internal class HttpServer(
    private val transport: StreamTransport,
    private val routes: Routes,
    private val limits: ServerLimits,
) : TransportCompletions {
    private val clients = mutableMapOf<ConnectionId, HttpConnection>()
    private val suspended = mutableSetOf<ConnectionId>()
    private val handlerScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val launchHandler: ((suspend () -> Response) -> SuspendedGet)? =
        if (routes.hasSuspendingGets) ({ run -> SuspendedGet(handlerScope.value, run) }) else null
    private val clock = TimeSource.Monotonic.markNow()
    private var nextOperation = 1L
    private var drainDeadline: Long? = null
    private var batchTime = -1L

    fun run(stopRequested: () -> Boolean) {
        try {
            runLoop(stopRequested)
        } finally {
            clients.values.forEach { it.close() }
            if (handlerScope.isInitialized()) handlerScope.value.cancel()
        }
    }

    private fun runLoop(stopRequested: () -> Boolean) {
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
            var timeout = if (deadline == null) 250 else minOf(250L, deadline - beforePoll).toInt()
            if (suspended.isNotEmpty()) timeout = minOf(timeout, 2)
            batchTime = -1L
            transport.poll(timeout, this)
            val afterPoll = completionTime()
            if (afterPoll >= nextSweep) {
                closeMatching { it.expired(afterPoll) }
                nextSweep = afterPoll + 250
            }
            finishHandlers(afterPoll)
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
            if (client.output == null) disconnect(connection) else client.beginDrain()
            return
        }
        val time = completionTime()
        client.received(byteCount, time)
        advance(connection, client, time)
    }

    override fun wrote(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        val client = clients[connection] ?: return
        client.writePending = false
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
        if (client.writePending) return
        val result = if (client.prepareResponse(routes, time, launchHandler)) {
            val output = checkNotNull(client.output)
            client.writePending = true
            transport.write(
                id, OperationId(nextOperation++), output, client.outputOffset, client.outputLength - client.outputOffset,
            )
        } else {
            if (client.awaitingHandler) suspended.add(id)
            val input = client.input
            val available = input.bytes.size - input.size
            if (client.readPending || available == 0) return
            client.readPending = true
            if (client.awaitingHandler) {
                val buffer = client.monitorBuffer()
                transport.read(id, OperationId(nextOperation++), buffer, 0, minOf(buffer.size, available))
            } else {
                transport.read(id, OperationId(nextOperation++), input.bytes, input.size, available)
            }
        }
        if (result != Submission.ACCEPTED) disconnect(id)
    }

    private fun finishHandlers(time: Long) {
        if (suspended.isEmpty()) return
        val iterator = suspended.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val client = clients.getValue(id)
            if (client.finishHandler(time)) {
                iterator.remove()
                advance(id, client, time)
            }
        }
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
                suspended.remove(id)
                connection.close()
                transport.closeConnection(id)
            }
        }
    }

    private fun disconnect(id: ConnectionId) {
        suspended.remove(id)
        clients.remove(id)?.close()
        transport.closeConnection(id)
    }

    private fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    private fun completionTime(): Long {
        // Sample after the wait, once for the readiness batch.
        if (batchTime < 0) batchTime = now()
        return batchTime
    }
}
