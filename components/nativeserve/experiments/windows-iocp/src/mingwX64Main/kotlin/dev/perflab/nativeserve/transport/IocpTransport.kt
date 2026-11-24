@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.transport

import dev.perflab.nativeserve.probe.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy

private const val BATCH_SIZE = 64
private val INVALID_WINDOWS_SOCKET = ULong.MAX_VALUE

internal fun openIocpTransport(host: String, port: Int, connectionLimit: Int): StreamTransport = memScoped {
    val octets = host.split('.')
    require(octets.size == 4) { "Expected a numeric IPv4 bind address" }
    var address = 0u
    for (octet in octets) {
        val value = octet.toUByteOrNull()
        require(value != null && octet.all { it in '0'..'9' }) { "Expected a numeric IPv4 bind address" }
        address = (address shl 8) or value.toUInt()
    }
    val status = alloc<UIntVar>()
    val state = checkNotNull(ns_open_iocp(address, port, status.ptr)) {
        "Could not initialize the IOCP listener on $host:$port (Windows error ${status.value})"
    }
    IocpTransport(state, connectionLimit)
}

private class IocpTransport(
    private val state: CPointer<ns_iocp>,
    private val connectionLimit: Int,
) : StreamTransport {
    private val peers = mutableMapOf<ConnectionId, Peer>()
    private val pending = mutableMapOf<CPointer<ns_io>, Pending>()
    private val failures = ArrayDeque<Pair<Pending, UInt>>()
    private var acceptOperation: Accept? = null
    private var nextConnection = 1L
    private var listening = true
    private var closed = false

    override fun accept(operation: OperationId): Submission {
        if (!listening) return Submission.CLOSED
        if (acceptOperation != null) return Submission.AT_CAPACITY
        val accept = Accept(operation)
        if (!arm(accept)) return Submission.AT_CAPACITY
        acceptOperation = accept
        return Submission.ACCEPTED
    }

    private fun arm(accept: Accept): Boolean {
        val io = ns_new_io(0) ?: return false
        accept.socket = ns_open_socket()
        if (accept.socket == INVALID_WINDOWS_SOCKET) {
            ns_free_io(io)
            return false
        }
        accept.io = io
        submit(accept, ns_submit_accept(state, accept.socket, io))
        return true
    }

    override fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission =
        transfer(connection, operation, bytes, offset, length, writing = false)

    override fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission =
        transfer(connection, operation, bytes, offset, length, writing = true)

    private fun transfer(
        connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int, writing: Boolean,
    ): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        if (peer.closed) return Submission.CLOSED
        if (if (writing) peer.writing else peer.reading) return Submission.AT_CAPACITY
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        val io = ns_new_io(length) ?: return Submission.AT_CAPACITY
        val transfer = Transfer(operation, io, peer, bytes, offset, length, writing)
        if (writing) {
            peer.writing = true
            bytes.usePinned { memcpy(ns_io_buffer(io), it.addressOf(offset), length.convert()) }
        } else {
            peer.reading = true
        }
        submit(transfer, ns_submit_io(peer.socket, io, if (writing) 1 else 0))
        return Submission.ACCEPTED
    }

    private fun submit(operation: Pending, status: UInt) {
        if (status == 0u) pending[operation.io] = operation
        else failures.addLast(operation to status)
    }

    override fun cancel(operation: OperationId) {
        val work = pending.values.firstOrNull { it.id == operation } ?: return
        when (work) {
            is Accept -> if (listening) ns_cancel_io(state, INVALID_WINDOWS_SOCKET, work.io)
            is Transfer -> if (!work.peer.closed) ns_cancel_io(state, work.peer.socket, work.io)
        }
    }

    override fun closeListener() {
        if (!listening) return
        listening = false
        ns_close_listener(state)
    }

    override fun closeConnection(connection: ConnectionId) {
        val peer = peers[connection] ?: return
        if (peer.closed) return
        peer.closed = true
        ns_close_socket(peer.socket)
        retire(peer)
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        val initialFailures = minOf(BATCH_SIZE, failures.size)
        repeat(initialFailures) {
            if (closed) return
            val (operation, status) = failures.removeFirst()
            finish(operation, 0, status, completions)
        }

        memScoped {
            val outcome = alloc<ns_completion>()
            repeat(BATCH_SIZE) { index ->
                if (closed) return
                val timeout = if (index == 0 && initialFailures == 0) timeoutMillis else 0
                val operation = take(outcome, timeout) ?: return
                finish(operation, outcome.count.toInt(), outcome.error, completions)
            }
        }
    }

    private fun take(outcome: ns_completion, timeout: Int): Pending? {
        val result = ns_take_completion(state, timeout, outcome.ptr)
        check(result >= 0) { "Could not retrieve IOCP completion (Windows error ${outcome.error})" }
        if (result == 0) return null
        return checkNotNull(pending.remove(outcome.operation)) { "IOCP returned an untracked completion" }
    }

    private fun finish(operation: Pending, count: Int, status: UInt, completions: TransportCompletions) {
        when (operation) {
            is Accept -> accepted(operation, status, completions)
            is Transfer -> transferred(operation, count, status, completions)
        }
    }

    private fun accepted(accept: Accept, status: UInt, completions: TransportCompletions) {
        ns_free_io(accept.io)
        acceptOperation = null
        if (listening && status == 0u && peers.size < connectionLimit &&
            ns_attach_accepted(state, accept.socket) != 0
        ) {
            val id = ConnectionId(nextConnection++)
            peers[id] = Peer(id, accept.socket)
            completions.accepted(accept.id, id)
            return
        }

        ns_close_socket(accept.socket)
        if (listening && (status == NS_PEER_RESET.toUInt() || status == NS_PEER_GONE.toUInt() ||
                status == 0u && peers.size >= connectionLimit) && arm(accept)
        ) {
            acceptOperation = accept
            return
        }
        val reason = if (!listening || status == NS_IO_CANCELLED.toUInt()) TransportFailure.CANCELLED
        else TransportFailure.IO_FAILURE
        completions.failed(null, accept.id, reason)
    }

    private fun transferred(work: Transfer, count: Int, status: UInt, completions: TransportCompletions) {
        val peer = work.peer
        if (work.writing) peer.writing = false else peer.reading = false
        val failure = when {
            peer.closed || status == NS_IO_CANCELLED.toUInt() -> TransportFailure.CANCELLED
            status != 0u || work.writing && count == 0 -> TransportFailure.CONNECTION_LOST
            else -> null
        }
        if (failure == null) {
            check(count in 0..work.length)
            if (!work.writing && count > 0) {
                work.bytes.usePinned { memcpy(it.addressOf(work.offset), ns_io_buffer(work.io), count.convert()) }
            }
        }
        ns_free_io(work.io)
        retire(peer)
        when {
            failure != null -> completions.failed(peer.id, work.id, failure)
            work.writing -> completions.wrote(peer.id, work.id, count)
            else -> completions.read(peer.id, work.id, count)
        }
    }

    private fun retire(peer: Peer) {
        if (peer.closed && !peer.reading && !peer.writing) peers.remove(peer.id)
    }

    override fun close() {
        if (closed) return
        closed = true
        closeListener()
        peers.keys.toList().forEach(::closeConnection)
        // Never release an OVERLAPPED or payload before the kernel's terminal packet.
        memScoped {
            val outcome = alloc<ns_completion>()
            while (pending.isNotEmpty()) release(checkNotNull(take(outcome, timeout = -1)))
        }
        for ((operation, _) in failures) release(operation)
        failures.clear()
        acceptOperation = null
        peers.clear()
        ns_close_iocp(state)
    }

    private fun release(operation: Pending) {
        if (operation is Accept) ns_close_socket(operation.socket)
        ns_free_io(operation.io)
    }

    private class Peer(val id: ConnectionId, val socket: ULong) {
        var closed = false
        var reading = false
        var writing = false
    }

    private sealed interface Pending {
        val id: OperationId
        val io: CPointer<ns_io>
    }

    private class Accept(override val id: OperationId) : Pending {
        override lateinit var io: CPointer<ns_io>
        var socket = INVALID_WINDOWS_SOCKET
    }

    private class Transfer(
        override val id: OperationId,
        override val io: CPointer<ns_io>,
        val peer: Peer,
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
        val writing: Boolean,
    ) : Pending
}
