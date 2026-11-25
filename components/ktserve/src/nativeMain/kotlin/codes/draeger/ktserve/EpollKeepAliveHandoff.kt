@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.AdaptiveEpollTransport
import codes.draeger.ktserve.transport.DetachedEpollPeer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeMark

/** One bounded batch per activation. Each socket already holds a shared admission slot. */
internal class EpollKeepAliveHandoff(
    private val clock: TimeMark,
    private val limits: ServerLimits,
    private val report: (String) -> Unit,
) {
    private class Transfer(val socket: DetachedEpollPeer, val http: IdleHttpConnection)

    private val pending = AtomicReference<ArrayDeque<Transfer>?>(null)
    private val signalTarget = AtomicReference<(() -> Unit)?>(null)
    var sent = false
        private set
    private var settled = false
    private var splitAfter = 0L
    private var retryAfter = 0L

    fun targetReady(transport: AdaptiveEpollTransport) {
        signalTarget.store(checkNotNull(transport.signalApplicationProgress))
    }

    fun split(source: AdaptiveEpollTransport, http: HttpServer) {
        val signal = signalTarget.load() ?: return
        val now = clock.elapsedNow().inWholeMilliseconds
        if (now < splitAfter) return
        splitAfter = now + 250
        val batch = ArrayDeque<Transfer>()
        http.splitIdleConnections(minOf(500L, limits.idleMillis)) move@ { id, state ->
            val socket = source.detachIdlePeer(id) ?: return@move false
            batch.addLast(Transfer(socket, state))
            true
        }
        if (batch.isEmpty()) return
        sent = true
        val size = batch.size
        pending.store(batch)
        report("handoff_sent=$size")
        signal()
    }

    // The receiver owns the batch until its job completes. The primary can recover a
    // remaining batch after that point, including when starting the second loop fails.
    fun receive(target: AdaptiveEpollTransport, http: HttpServer, event: String = "handoff_received"): Boolean {
        if (settled) return true
        val batch = pending.load() ?: return true
        val now = clock.elapsedNow().inWholeMilliseconds
        if (now < retryAfter) return false
        var received = 0
        while (batch.isNotEmpty()) {
            val transfer = batch.first()
            if (now - transfer.http.lastActivity >= limits.idleMillis) {
                batch.removeFirst().socket.discard()
                continue
            }
            val id = target.adoptIdlePeer(transfer.socket)
            if (id == null) {
                // Retain ownership while epoll registration resources recover.
                retryAfter = now + 100
                break
            }
            batch.removeFirst()
            http.resumeIdleConnection(id, transfer.http)
            received++
        }
        if (received != 0) report("$event=$received")
        if (batch.isNotEmpty()) return false
        pending.store(null)
        settled = true
        return true
    }

    // Shutdown awaits the receiving job first.
    fun close() {
        pending.exchange(null)?.forEach { it.socket.discard() }
    }
}
