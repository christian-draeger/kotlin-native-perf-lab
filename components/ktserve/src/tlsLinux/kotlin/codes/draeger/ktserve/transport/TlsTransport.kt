@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.transport

import codes.draeger.ktserve.openssl.ktserve_tls_close_notify
import codes.draeger.ktserve.openssl.ktserve_tls_free
import codes.draeger.ktserve.openssl.ktserve_tls_handshake
import codes.draeger.ktserve.openssl.ktserve_tls_pending
import codes.draeger.ktserve.openssl.ktserve_tls_read
import codes.draeger.ktserve.openssl.ktserve_tls_session
import codes.draeger.ktserve.openssl.ktserve_tls_write
import kotlin.time.TimeSource
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLL_CLOEXEC
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.EAGAIN
import platform.posix.ECONNABORTED
import platform.posix.EHOSTDOWN
import platform.posix.EHOSTUNREACH
import platform.posix.EINTR
import platform.posix.EMFILE
import platform.posix.ENETDOWN
import platform.posix.ENETUNREACH
import platform.posix.ENFILE
import platform.posix.ENOBUFS
import platform.posix.ENOMEM
import platform.posix.ENONET
import platform.posix.ENOPROTOOPT
import platform.posix.EOPNOTSUPP
import platform.posix.EPERM
import platform.posix.EPROTO
import platform.posix.ETIMEDOUT
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.TCP_NODELAY
import platform.posix.accept
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.memset
import platform.posix.setsockopt

private const val BATCH_SIZE = 64
private const val LISTENER_TOKEN = 0UL
private const val HANDSHAKE_MILLIS = 10_000L
private const val LISTENER_RETRY_MILLIS = 100L
private const val TLS_WANT_READ = -2
private const val TLS_WANT_WRITE = -3

/** The caller keeps [context] and this native memory scope alive until the transport has closed. */
internal fun MemScope.openTlsTransport(host: String, port: Int, connectionLimit: Int, context: COpaquePointer): StreamTransport {
    val events = allocArray<epoll_event>(BATCH_SIZE)
    val change = alloc<epoll_event>()
    memset(change.ptr, 0, sizeOf<epoll_event>().convert())
    val noDelay = alloc<IntVar> { value = 1 }
    val listener = openListener(host, port)
    val poller = epoll_create1(EPOLL_CLOEXEC)
    if (poller < 0) {
        val cause = errno
        close(listener)
        error("TLS epoll_create1 failed (errno $cause)")
    }
    return TlsTransport(listener, poller, connectionLimit, context, events, change.ptr, noDelay.ptr)
}

/** OpenSSL sessions and their socket descriptors belong to the reactor thread. */
private class TlsTransport(
    private var listener: Int,
    private var poller: Int,
    private val connectionLimit: Int,
    private val context: COpaquePointer,
    private val events: CPointer<epoll_event>,
    private val change: CPointer<epoll_event>,
    private val noDelay: CPointer<IntVar>,
) : StreamTransport {
    private val clock = TimeSource.Monotonic.markNow()
    private val peers = mutableMapOf<ConnectionId, Peer>()
    // The insertion order is also deadline order. Established peers do not enter the sweep.
    private val handshakes = linkedMapOf<ConnectionId, Peer>()
    private val work = ArrayDeque<Peer>()
    private val failures = ArrayDeque<FailedTransfer>()
    private var nextConnection = 1L
    private var acceptOperation: OperationId? = null
    private var acceptCancelled = false
    private var listenerWatched = false
    private var listenerRetryAt = 0L

    override fun accept(operation: OperationId): Submission {
        if (listener < 0) return Submission.CLOSED
        if (acceptOperation != null) return Submission.AT_CAPACITY
        acceptOperation = operation
        return Submission.ACCEPTED
    }

    override fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        return submit(peer, peer.read, operation, bytes, offset, length)
    }

    override fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        return submit(peer, peer.write, operation, bytes, offset, length)
    }

    private fun submit(peer: Peer, transfer: Transfer, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission {
        if (transfer.bytes != null) return Submission.AT_CAPACITY
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        transfer.operation = operation
        transfer.bytes = bytes
        transfer.offset = offset
        transfer.length = length
        transfer.waitFor = 0U
        queue(peer)
        return Submission.ACCEPTED
    }

    override fun cancel(operation: OperationId) {
        if (acceptOperation == operation) {
            acceptCancelled = true
            return
        }
        for (peer in peers.values) {
            val transfer = when {
                peer.read.matches(operation) -> peer.read
                peer.write.matches(operation) -> peer.write
                else -> continue
            }
            if (transfer.waitFor != 0U) {
                // An OpenSSL operation that needs a retry can have changed the TLS stream already.
                retire(peer, TransportFailure.CANCELLED)
            } else {
                failTransfer(peer, transfer, TransportFailure.CANCELLED)
                queue(peer)
            }
            return
        }
    }

    override fun closeListener() {
        if (listener < 0) return
        close(listener)
        listener = -1
        listenerWatched = false
        acceptCancelled = acceptOperation != null
    }

    override fun closeConnection(connection: ConnectionId) {
        val peer = peers[connection] ?: return
        retire(peer, TransportFailure.CANCELLED, notifyPeer = true)
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        if (poller < 0) return
        val time = now()
        val handshakeWait = expireHandshakes(time)
        val queued = minOf(work.size, BATCH_SIZE)
        repeat(queued) {
            val peer = work.removeFirst()
            peer.queued = false
            process(peer, 0U, completions)
            if (poller < 0) return
        }

        val hadFailures = acceptCancelled || failures.isNotEmpty()
        deliverFailures(completions)
        if (poller < 0) return
        val listenerWait = refreshListener(time)
        val requestedWait = if (timeoutMillis < 0) Int.MAX_VALUE else timeoutMillis
        val timeout = if (queued > 0 || hadFailures) 0 else minOf(requestedWait, handshakeWait, listenerWait)
        val count = epoll_wait(poller, events, BATCH_SIZE, timeout)
        if (count < 0 && errno == EINTR) return
        check(count >= 0) { "TLS epoll_wait failed (errno $errno)" }
        for (index in 0 until count) {
            if (poller < 0) return
            val event = events[index]
            val token = event.data.u64
            if (token == LISTENER_TOKEN) acceptReady(completions)
            else peers[ConnectionId(token.toLong())]?.let { process(it, event.events, completions) }
        }
        if (count == 0 && handshakes.isNotEmpty()) expireHandshakes(now())
        deliverFailures(completions)
    }

    private fun acceptReady(completions: TransportCompletions) {
        repeat(BATCH_SIZE) {
            val operation = acceptOperation ?: return
            if (listener < 0 || acceptCancelled) return
            val fd = accept(listener, null, null)
            if (fd < 0) {
                when (val cause = errno) {
                    EAGAIN -> return
                    EINTR, ECONNABORTED, EPERM, ENETDOWN, EPROTO, ENOPROTOOPT,
                    EHOSTDOWN, ENONET, EHOSTUNREACH, EOPNOTSUPP, ENETUNREACH, ETIMEDOUT -> return@repeat
                    EMFILE, ENFILE, ENOBUFS, ENOMEM -> {
                        listenerRetryAt = now() + LISTENER_RETRY_MILLIS
                        return
                    }
                    else -> {
                        acceptOperation = null
                        println("ktServe TLS accept failed (errno $cause)")
                        completions.failed(null, operation, TransportFailure.IO_FAILURE)
                        return
                    }
                }
            }
            val peer = adopt(fd, completions) ?: return@repeat
            acceptOperation = null
            completions.accepted(operation, peer.id)
        }
    }

    private fun adopt(fd: Int, completions: TransportCompletions): Peer? {
        if (peers.size >= connectionLimit) {
            close(fd)
            completions.admissionRejected()
            return null
        }
        var adopted = false
        try {
            if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0 || fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) return null
            if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, noDelay, sizeOf<IntVar>().convert()) < 0) return null
            val session = ktserve_tls_session(context, fd) ?: return null
            val id = ConnectionId(nextConnection++)
            val peer = Peer(id, fd, session, now() + HANDSHAKE_MILLIS)
            if (watch(EPOLL_CTL_ADD, fd, id.value.toULong(), EPOLLIN) < 0) {
                ktserve_tls_free(session)
                return null
            }
            peers[id] = peer
            handshakes[id] = peer
            adopted = true
            return peer
        } finally {
            if (!adopted) close(fd)
        }
    }

    private fun process(peer: Peer, ready: UInt, completions: TransportCompletions) {
        if (peer.closed) return
        if (ready and (EPOLLERR or EPOLLHUP) != 0U) {
            retire(peer, TransportFailure.CONNECTION_LOST)
            return
        }
        if (peer.handshakeInterest != 0U) {
            if (ready and peer.handshakeInterest == 0U) return
            if (peer.handshakeDeadline <= now()) {
                retire(peer, TransportFailure.CONNECTION_LOST)
                return
            }
            when (val result = ktserve_tls_handshake(peer.session)) {
                1 -> {
                    peer.handshakeInterest = 0U
                    handshakes.remove(peer.id)
                }
                TLS_WANT_READ, TLS_WANT_WRITE -> {
                    peer.handshakeInterest = waitFor(result)
                    updateInterest(peer)
                    return
                }
                else -> {
                    retire(peer, TransportFailure.CONNECTION_LOST)
                    return
                }
            }
        }

        // A retry of SSL_write can have encrypted part of the response already. SSL_read also
        // takes priority if it must emit protocol data before it can proceed.
        val retry = peer.retry
        if (retry != null) {
            if (ready and retry.waitFor != 0U) complete(peer, retry, completions)
        } else {
            if (peer.read.bytes != null && (peer.read.waitFor == 0U || ready and EPOLLIN != 0U ||
                    ktserve_tls_pending(peer.session) > 0)
            ) {
                complete(peer, peer.read, completions)
            }
            if (!peer.closed && peer.retry == null && peer.write.bytes != null) complete(peer, peer.write, completions)
        }

        if (peer.closed) return
        updateInterest(peer)
        if (!peer.closed && peer.retry == null &&
            (peer.write.bytes != null || peer.read.bytes != null &&
                (peer.read.waitFor == 0U || ktserve_tls_pending(peer.session) > 0))
        ) queue(peer)
    }

    private fun complete(peer: Peer, transfer: Transfer, completions: TransportCompletions) {
        val bytes = checkNotNull(transfer.bytes)
        val count = bytes.usePinned { pinned ->
            val range = pinned.addressOf(transfer.offset)
            if (transfer.writing) ktserve_tls_write(peer.session, range, transfer.length)
            else ktserve_tls_read(peer.session, range, transfer.length)
        }
        if (count == TLS_WANT_READ || count == TLS_WANT_WRITE) {
            transfer.waitFor = waitFor(count)
            return
        }
        if (count < 0 || transfer.writing && count == 0) {
            retire(peer, TransportFailure.CONNECTION_LOST)
            return
        }
        val operation = transfer.operation
        transfer.clear()
        if (transfer.writing) {
            peer.wrote = true
            completions.wrote(peer.id, operation, count)
        } else {
            completions.read(peer.id, operation, count)
        }
    }

    private fun updateInterest(peer: Peer) {
        val interest = when {
            peer.handshakeInterest != 0U -> peer.handshakeInterest
            else -> peer.retry?.waitFor ?: (
                (if (peer.read.bytes != null) EPOLLIN else 0U) or
                    (if (peer.write.bytes != null) EPOLLOUT else 0U)
                )
        }
        if (interest == peer.interest) return
        val action = when {
            interest == 0U -> EPOLL_CTL_DEL
            peer.interest == 0U -> EPOLL_CTL_ADD
            else -> EPOLL_CTL_MOD
        }
        if (watch(action, peer.fd, peer.id.value.toULong(), interest) < 0) {
            retire(peer, TransportFailure.IO_FAILURE)
        } else {
            peer.interest = interest
        }
    }

    private fun expireHandshakes(time: Long): Int {
        while (handshakes.isNotEmpty()) {
            val peer = handshakes.values.first()
            val remaining = peer.handshakeDeadline - time
            if (remaining > 0) return remaining.toInt()
            retire(peer, TransportFailure.CONNECTION_LOST)
        }
        return Int.MAX_VALUE
    }

    private fun refreshListener(time: Long): Int {
        if (listener < 0) return Int.MAX_VALUE
        val remaining = (listenerRetryAt - time).coerceAtLeast(0).toInt()
        val active = acceptOperation != null && !acceptCancelled && remaining == 0
        if (active != listenerWatched) {
            val action = if (active) EPOLL_CTL_ADD else EPOLL_CTL_DEL
            check(watch(action, listener, LISTENER_TOKEN, if (active) EPOLLIN else 0U) >= 0) {
                "TLS epoll listener registration failed (errno $errno)"
            }
            listenerWatched = active
        }
        return if (remaining == 0) Int.MAX_VALUE else remaining
    }

    private fun watch(action: Int, fd: Int, token: ULong, interest: UInt): Int {
        change.pointed.data.u64 = token
        change.pointed.events = interest
        return epoll_ctl(poller, action, fd, change)
    }

    private fun queue(peer: Peer) {
        if (peer.queued) return
        peer.queued = true
        work.addLast(peer)
    }

    private fun retire(peer: Peer, failure: TransportFailure, notifyPeer: Boolean = false) {
        peers.remove(peer.id)
        handshakes.remove(peer.id)
        if (notifyPeer && peer.wrote && peer.write.bytes == null && peer.retry == null) {
            ktserve_tls_close_notify(peer.session)
        }
        peer.release()
        failTransfer(peer, peer.read, failure)
        failTransfer(peer, peer.write, failure)
    }

    private fun failTransfer(peer: Peer, transfer: Transfer, failure: TransportFailure) {
        if (transfer.bytes == null) return
        failures.addLast(FailedTransfer(peer.id, transfer.operation, failure))
        transfer.clear()
    }

    private fun deliverFailures(completions: TransportCompletions) {
        if (acceptCancelled) {
            val operation = checkNotNull(acceptOperation)
            acceptCancelled = false
            acceptOperation = null
            completions.failed(null, operation, TransportFailure.CANCELLED)
        }
        repeat(minOf(failures.size, BATCH_SIZE)) {
            if (poller < 0) return
            val failure = failures.removeFirst()
            completions.failed(failure.connection, failure.operation, failure.reason)
        }
    }

    override fun close() {
        if (poller < 0) return
        closeListener()
        for (peer in peers.values) {
            peer.release()
            peer.read.clear()
            peer.write.clear()
        }
        peers.clear()
        handshakes.clear()
        work.clear()
        failures.clear()
        acceptOperation = null
        acceptCancelled = false
        close(poller)
        poller = -1
    }

    private fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    private class Peer(val id: ConnectionId, val fd: Int, val session: COpaquePointer, val handshakeDeadline: Long) {
        val read = Transfer(writing = false)
        val write = Transfer(writing = true)
        var interest = EPOLLIN
        var handshakeInterest = EPOLLIN
        var queued = false
        var closed = false
            private set
        var wrote = false

        val retry: Transfer?
            get() = when {
                write.waitFor != 0U -> write
                read.waitFor == EPOLLOUT -> read
                else -> null
            }

        fun release() {
            closed = true
            ktserve_tls_free(session)
            close(fd)
        }
    }

    /** Leases this range across retries, while Kotlin pins it only during each synchronous call. */
    private class Transfer(val writing: Boolean) {
        var operation = OperationId(0)
        var bytes: ByteArray? = null
        var offset = 0
        var length = 0
        var waitFor = 0U

        fun matches(id: OperationId): Boolean = bytes != null && operation == id

        fun clear() {
            bytes = null
            waitFor = 0U
        }
    }

    private data class FailedTransfer(val connection: ConnectionId, val operation: OperationId, val reason: TransportFailure)
}

private fun waitFor(result: Int): UInt = if (result == TLS_WANT_READ) EPOLLIN else EPOLLOUT
