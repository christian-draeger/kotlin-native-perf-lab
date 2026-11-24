@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.transport

import cnames.structs.ns_ring
import dev.perflab.nativeserve.uring.*
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.ECANCELED
import platform.posix.ECONNABORTED
import platform.posix.EINTR
import platform.posix.SHUT_RDWR
import platform.posix.close
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.shutdown

private const val BATCH_SIZE = 64
internal const val URING_MAX_CONNECTIONS = 1024

internal class UringUnavailable(val errorCode: Int) : IllegalStateException("io_uring unavailable (errno $errorCode)")

internal fun MemScope.probeUring(): UInt {
    val ring = newRing(8)
    return try {
        ns_ring_features(ring)
    } finally {
        ns_ring_close(ring)
    }
}

private fun MemScope.newRing(entries: Int): CPointer<ns_ring> {
    val error = alloc<IntVar>()
    return ns_ring_open(entries.toUInt(), error.ptr) ?: throw UringUnavailable(-error.value)
}

internal fun MemScope.openUringTransport(host: String, port: Int, connectionLimit: Int): StreamTransport {
    require(connectionLimit in 1..URING_MAX_CONNECTIONS)
    // At most one cancellation SQE per live operation and one accept or two transfers per connection.
    val workLimit = 2 * connectionLimit + 2
    val scratch = alloc<ns_cqe>()
    val ring = newRing(2 * workLimit)
    try {
        val listener = openListener(host, port, nonBlocking = false)
        println("NativeServe backend=io_uring tcp=ipv4 features=0x${ns_ring_features(ring).toString(16)}")
        return UringTransport(ring, scratch, listener, connectionLimit, workLimit)
    } catch (cause: Throwable) {
        ns_ring_close(ring)
        throw cause
    }
}

private class UringTransport(
    private val ring: CPointer<ns_ring>,
    private val scratch: ns_cqe,
    private var listener: Int,
    private val connectionLimit: Int,
    private val workLimit: Int,
) : StreamTransport {
    private val peers = mutableMapOf<ConnectionId, Peer>()
    private val operations = mutableMapOf<Long, Work>()
    private var listenOpen = true
    private var closed = false
    private var accept: Accept? = null
    private var nextConnection = 1L
    private var nextToken = 1L

    override fun accept(operation: OperationId): Submission {
        if (!listenOpen) return Submission.CLOSED
        if (accept != null || operations.size >= workLimit) return Submission.AT_CAPACITY
        val work = Accept(operation, nextToken++)
        if (ns_ring_accept(ring, work.token.toULong(), listener) == 0) return Submission.AT_CAPACITY
        operations[work.token] = work
        accept = work
        return Submission.ACCEPTED
    }

    override fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission =
        transfer(connection, operation, bytes, offset, length, writing = false)

    override fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission =
        transfer(connection, operation, bytes, offset, length, writing = true)

    private fun transfer(
        connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int, writing: Boolean,
    ): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        if (peer.closing) return Submission.CLOSED
        if ((if (writing) peer.write else peer.read) != null || operations.size >= workLimit - 1) return Submission.AT_CAPACITY
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        val payload = malloc(length.convert()) ?: return Submission.AT_CAPACITY
        if (writing) bytes.usePinned { memcpy(payload, it.addressOf(offset), length.convert()) }
        val work = Transfer(operation, nextToken++, peer, bytes, offset, length, writing, payload)
        if (ns_ring_transfer(ring, work.token.toULong(), peer.fd, payload, length, if (writing) 1 else 0) == 0) {
            free(payload)
            return Submission.AT_CAPACITY
        }
        if (writing) peer.write = work else peer.read = work
        operations[work.token] = work
        return Submission.ACCEPTED
    }

    override fun cancel(operation: OperationId) {
        operations.values.firstOrNull { it.operation == operation }?.let(::requestCancellation)
    }

    private fun requestCancellation(work: Work) {
        if (work.cancelRequested || work.done) return
        check(ns_ring_cancel(ring, (-work.token).toULong(), work.token.toULong()) != 0) {
            "io_uring did not have a reserved cancellation slot"
        }
        work.cancelRequested = true
        work.cancelPending = true
    }

    override fun closeListener() {
        if (!listenOpen) return
        listenOpen = false
        val work = accept
        if (work != null) requestCancellation(work) else releaseListener()
    }

    private fun releaseListener() {
        if (listener < 0) return
        close(listener)
        listener = -1
    }

    override fun closeConnection(connection: ConnectionId) {
        val peer = peers[connection] ?: return
        if (peer.closing) return
        peer.closing = true
        peer.read?.let(::requestCancellation)
        peer.write?.let(::requestCancellation)
        shutdown(peer.fd, SHUT_RDWR)
        releasePeer(peer)
    }

    private fun releasePeer(peer: Peer) {
        if (!peer.closing || peer.read != null || peer.write != null) return
        peers.remove(peer.id)
        close(peer.fd)
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        if (closed) return
        submit()
        repeat(BATCH_SIZE) { index ->
            if (closed || !take(if (index == 0) timeoutMillis else 0)) return
            deliver(completions)
        }
    }

    private fun submit() {
        val error = ns_ring_submit(ring)
        check(error == 0) { "io_uring submission failed (errno ${-error})" }
    }

    private fun take(timeout: Int): Boolean {
        val result = ns_ring_take(ring, timeout, scratch.ptr)
        check(result >= 0) { "io_uring wait failed (errno ${-result})" }
        return result != 0
    }

    private fun deliver(completions: TransportCompletions?) {
        val token = scratch.token.toLong()
        val result = scratch.result
        check(scratch.more == 0) { "io_uring returned a nonterminal result for a single-shot request" }
        if (token < 0) {
            val work = operations.getValue(-token)
            work.cancelPending = false
            if (work.done) operations.remove(work.token)
            return
        }
        val work = operations.getValue(token)
        work.done = true
        if (!work.cancelPending) operations.remove(token)
        when (work) {
            is Accept -> accepted(work, result, completions)
            is Transfer -> transferred(work, result, completions)
        }
    }

    private fun accepted(work: Accept, result: Int, completions: TransportCompletions?) {
        accept = null
        if (!listenOpen) {
            releaseListener()
            if (result >= 0) close(result)
            completions?.failed(null, work.operation, TransportFailure.CANCELLED)
        } else if (result >= 0 && peers.size < connectionLimit) {
            val id = ConnectionId(nextConnection++)
            peers[id] = Peer(id, result)
            checkNotNull(completions).accepted(work.operation, id)
        } else {
            if (result >= 0) close(result)
            if ((result >= 0 || result == -EINTR || result == -ECONNABORTED) &&
                accept(work.operation) == Submission.ACCEPTED
            ) return
            val failure = if (result == -ECANCELED) TransportFailure.CANCELLED else TransportFailure.IO_FAILURE
            completions?.failed(null, work.operation, failure)
        }
    }

    private fun transferred(work: Transfer, result: Int, completions: TransportCompletions?) {
        val peer = work.peer
        if (work.writing) peer.write = null else peer.read = null
        val failure = when {
            peer.closing || result == -ECANCELED -> TransportFailure.CANCELLED
            result < 0 || work.writing && result == 0 -> TransportFailure.CONNECTION_LOST
            else -> null
        }
        if (failure == null) {
            check(result <= work.length)
            if (!work.writing && result > 0) {
                work.bytes.usePinned { memcpy(it.addressOf(work.offset), work.payload, result.convert()) }
            }
        }
        free(work.payload)
        releasePeer(peer)
        when {
            failure != null -> completions?.failed(peer.id, work.operation, failure)
            work.writing -> completions?.wrote(peer.id, work.operation, result)
            else -> completions?.read(peer.id, work.operation, result)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        closeListener()
        peers.keys.toList().forEach(::closeConnection)
        submit()
        while (operations.isNotEmpty()) {
            if (take(-1)) deliver(null)
        }
        ns_ring_close(ring)
    }

    private class Peer(val id: ConnectionId, val fd: Int) {
        var closing = false
        var read: Transfer? = null
        var write: Transfer? = null
    }

    private sealed class Work(val operation: OperationId, val token: Long) {
        var done = false
        var cancelRequested = false
        var cancelPending = false
    }

    private class Accept(operation: OperationId, token: Long) : Work(operation, token)

    private class Transfer(
        operation: OperationId,
        token: Long,
        val peer: Peer,
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
        val writing: Boolean,
        val payload: COpaquePointer,
    ) : Work(operation, token)
}
