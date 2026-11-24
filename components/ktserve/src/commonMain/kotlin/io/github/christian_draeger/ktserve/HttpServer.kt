package io.github.christian_draeger.ktserve

import io.github.christian_draeger.ktserve.transport.ConnectionId
import io.github.christian_draeger.ktserve.transport.OperationId
import io.github.christian_draeger.ktserve.transport.StreamTransport
import io.github.christian_draeger.ktserve.transport.Submission
import io.github.christian_draeger.ktserve.transport.TransportCompletions
import io.github.christian_draeger.ktserve.transport.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.TimeSource

internal class HttpServerCounters {
    var accepted = 0L
    var requests = 0L
}

internal class HttpServer(
    private val transport: StreamTransport,
    private val routes: Routes,
    private val limits: ServerLimits,
    private val counters: HttpServerCounters? = null,
    timeSource: TimeSource = TimeSource.Monotonic,
) : TransportCompletions {
    private val clients = mutableMapOf<ConnectionId, HttpConnection>()
    private val suspended = mutableSetOf<ConnectionId>()
    private val signalApplicationProgress = transport.signalApplicationProgress
    private val handlerScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val dispatcher: () -> CoroutineScope = { handlerScope.value }
    private val countRequest: (() -> Unit)? = counters?.let { totals -> { totals.requests++ } }
    private val launchHandler: ((suspend () -> Response) -> SuspendedGet)? =
        if (routes.hasSuspendingGets || routes.applicationHandler != null) {
            { run -> SuspendedGet(handlerScope.value, signalApplicationProgress, run) }
        } else null
    private val clock = timeSource.markNow()
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
            if (signalApplicationProgress == null) {
                if (suspended.isNotEmpty()) timeout = minOf(timeout, 2)
            } else if (clients.isNotEmpty()) {
                // A readiness event can return shortly before the next timeout sweep.
                timeout = minOf(timeout.toLong(), (nextSweep - beforePoll).coerceAtLeast(0)).toInt()
            }
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
        counters?.let { it.accepted++ }
        val client = HttpConnection(time, limits, dispatcher, countRequest, signalApplicationProgress)
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
        val responseReady = client.prepareResponse(routes, time, launchHandler)
        val waiting = client.awaitingHandler || client.awaitingProducer
        if (waiting) suspended.add(id) else if (suspended.isNotEmpty()) suspended.remove(id)
        val result = if (responseReady) {
            val output = checkNotNull(client.output)
            client.writePending = true
            transport.write(
                id, OperationId(nextOperation++), output, client.outputOffset, client.outputLength - client.outputOffset,
            )
        } else {
            val input = client.input
            val available = input.bytes.size - input.size
            if (client.readPending || client.bodyBlocked || available == 0) return
            client.readPending = true
            if (waiting) {
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
        for (id in suspended.toList()) {
            val client = clients[id] ?: continue
            if (client.awaitingProducer) {
                if (!client.resumeProducer()) disconnect(id) else if (!client.awaitingProducer) advance(id, client, time)
            } else if (client.finishHandler(time) || client.bodyBlocked) {
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
