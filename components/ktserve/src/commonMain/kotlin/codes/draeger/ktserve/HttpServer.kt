package codes.draeger.ktserve

import codes.draeger.ktserve.transport.ConnectionId
import codes.draeger.ktserve.transport.OperationId
import codes.draeger.ktserve.transport.StreamTransport
import codes.draeger.ktserve.transport.Submission
import codes.draeger.ktserve.transport.TransportCompletions
import codes.draeger.ktserve.transport.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
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
    private val observation = routes.observer?.let { ServerObservation(it, timeSource) }
    private val signalApplicationProgress = transport.signalApplicationProgress
    private val handlerScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val dispatcher: () -> CoroutineScope = { handlerScope.value }
    private val countRequest: (() -> Unit)? = counters?.let { totals -> { totals.requests++ } }
    private val launchHandler: ((suspend () -> Response) -> SuspendedGet)? =
        if (routes.hasSuspendingGets || routes.applicationHandler != null) {
            { run -> SuspendedGet(handlerScope.value, signalApplicationProgress, run) }
        } else null
    private val blockingScope = if (routes.hasBlockingGets) CoroutineScope(SupervisorJob() + Dispatchers.IO) else null
    private val launchBlockingHandler: ((() -> Response) -> SuspendedGet?)? = blockingScope?.let { scope ->
        val permits = Semaphore(limits.blockingHandlers)
        return@let { run ->
            if (permits.tryAcquire()) {
                SuspendedGet(scope, signalApplicationProgress, run).also { it.releaseWhenDone(permits::release) }
            } else {
                null
            }
        }
    }
    private val clock = timeSource.markNow()
    private var nextOperation = 1L
    private var drainDeadline: Long? = null
    private var batchTime = -1L

    fun run(stopRequested: () -> Boolean) {
        try {
            runLoop(stopRequested)
        } finally {
            clients.values.forEach { it.close(ExchangeOutcome.SHUTDOWN) }
            if (clients.isNotEmpty()) observation?.emit(ServerEvent.Connections(0))
            if (handlerScope.isInitialized()) handlerScope.value.cancel()
            blockingScope?.cancel()
            observation?.emit(ServerEvent.Lifecycle.STOPPED)
        }
    }

    private fun runLoop(stopRequested: () -> Boolean) {
        val sweepMillis = minOf(250L, limits.responseMillis)
        var nextSweep = 0L
        acceptNext()
        observation?.emit(ServerEvent.Lifecycle.READY)
        while (true) {
            val beforePoll = now()
            if (drainDeadline == null && stopRequested()) {
                drainDeadline = beforePoll + limits.drainMillis
                transport.closeListener()
                observation?.emit(ServerEvent.Lifecycle.DRAINING)
                closeMatching { if (it.beginDrain()) null else ExchangeOutcome.SHUTDOWN }
            }
            val deadline = drainDeadline
            if (deadline != null && (clients.isEmpty() || beforePoll >= deadline)) break
            var timeout = if (deadline == null) sweepMillis.toInt() else minOf(sweepMillis, deadline - beforePoll).toInt()
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
                closeMatching { it.expiry(afterPoll)?.outcome() }
                nextSweep = afterPoll + sweepMillis
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
        val exchange = observation?.let { ExchangeObservation(it, connection.value) }
        val client = HttpConnection(time, limits, dispatcher, countRequest, signalApplicationProgress, exchange)
        clients[connection] = client
        observation?.emit(ServerEvent.Connections(clients.size))
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
        if (client.wrote(byteCount, time)) advance(connection, client, time)
        else disconnect(connection, ExchangeOutcome.IO_FAILURE)
    }

    override fun failed(connection: ConnectionId?, operation: OperationId, failure: TransportFailure) {
        if (connection != null) {
            val outcome = if (failure == TransportFailure.IO_FAILURE) ExchangeOutcome.IO_FAILURE
            else ExchangeOutcome.DISCONNECTED
            disconnect(connection, outcome)
        } else if (drainDeadline == null) {
            error("Listener operation failed ($failure)")
        }
    }

    override fun admissionRejected() {
        observation?.emit(ServerEvent.AdmissionRejected)
    }

    private fun advance(id: ConnectionId, client: HttpConnection, time: Long) {
        if (client.writePending) return
        val responseReady = client.prepareResponse(routes, time, launchHandler, launchBlockingHandler)
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
        if (result != Submission.ACCEPTED) disconnect(id, ExchangeOutcome.IO_FAILURE)
    }

    private fun finishHandlers(time: Long) {
        if (suspended.isEmpty()) return
        for (id in suspended.toList()) {
            val client = clients[id] ?: continue
            if (client.awaitingProducer) {
                if (!client.resumeProducer(time)) disconnect(id, ExchangeOutcome.IO_FAILURE)
                else if (!client.awaitingProducer) advance(id, client, time)
            } else if (client.finishHandler(time) || client.bodyBlocked) {
                advance(id, client, time)
            }
        }
    }

    private fun acceptNext() {
        check(transport.accept(OperationId(nextOperation++)) == Submission.ACCEPTED) { "Could not submit accept" }
    }

    private fun closeMatching(expiry: (HttpConnection) -> ExchangeOutcome?) {
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val (id, connection) = iterator.next()
            val outcome = expiry(connection) ?: continue
            iterator.remove()
            suspended.remove(id)
            connection.close(outcome)
            transport.closeConnection(id)
            observation?.emit(ServerEvent.Connections(clients.size))
        }
    }

    private fun disconnect(id: ConnectionId, outcome: ExchangeOutcome = ExchangeOutcome.DISCONNECTED) {
        suspended.remove(id)
        clients.remove(id)?.let { client ->
            client.close(outcome)
            observation?.emit(ServerEvent.Connections(clients.size))
        }
        transport.closeConnection(id)
    }

    private fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    private fun completionTime(): Long {
        // Sample after the wait, once for the readiness batch.
        if (batchTime < 0) batchTime = now()
        return batchTime
    }
}

private fun ExchangeExpiry.outcome(): ExchangeOutcome = when (this) {
    ExchangeExpiry.IDLE -> ExchangeOutcome.IDLE_TIMEOUT
    ExchangeExpiry.REQUEST_HEAD -> ExchangeOutcome.REQUEST_HEAD_TIMEOUT
    ExchangeExpiry.REQUEST_BODY -> ExchangeOutcome.REQUEST_BODY_TIMEOUT
    ExchangeExpiry.HANDLER -> ExchangeOutcome.HANDLER_TIMEOUT
    ExchangeExpiry.RESPONSE -> ExchangeOutcome.RESPONSE_TIMEOUT
}
