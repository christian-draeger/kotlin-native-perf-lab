@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.transport

import codes.draeger.ktserve.kqueue.KTSERVE_EVENT_BATCH_SIZE
import codes.draeger.ktserve.kqueue.ktserve_kqueue_open
import codes.draeger.ktserve.kqueue.ktserve_kqueue_poll
import codes.draeger.ktserve.kqueue.ktserve_kqueue_watch
import codes.draeger.ktserve.kqueue.ktserve_network_ipv4
import codes.draeger.ktserve.kqueue.ktserve_network_port
import codes.draeger.ktserve.kqueue.ktserve_ready_event
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.ECONNABORTED
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket

private const val BATCH_SIZE = KTSERVE_EVENT_BATCH_SIZE

internal fun MemScope.ipv4SocketAddress(host: String, port: Int): sockaddr_in {
    val address = alloc<sockaddr_in>()
    memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
    address.sin_len = sizeOf<sockaddr_in>().convert()
    address.sin_family = AF_INET.convert()
    address.sin_port = ktserve_network_port(port.toUShort())
    val octets = host.split('.')
    require(octets.size == 4) { "Expected a numeric IPv4 bind address" }
    val ipv4 = octets.fold(0U) { value, octet ->
        val number = octet.toUByteOrNull()
        require(number != null && octet.all { it in '0'..'9' }) { "Expected a numeric IPv4 bind address" }
        (value shl 8) or number.toUInt()
    }
    address.sin_addr.s_addr = ktserve_network_ipv4(ipv4)
    return address
}

internal fun MemScope.openPlatformTransport(host: String, port: Int, connectionLimit: Int): StreamTransport {
    val address = ipv4SocketAddress(host, port)
    val enabled = alloc<IntVar>()
    enabled.value = 1
    val events = allocArray<ktserve_ready_event>(BATCH_SIZE)
    val listener = checked(socket(AF_INET, SOCK_STREAM, 0), "socket")
    try {
        checked(fcntl(listener, F_SETFL, O_NONBLOCK), "set listener nonblocking")
        checked(fcntl(listener, F_SETFD, FD_CLOEXEC), "set listener close-on-exec")
        checked(setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, enabled.ptr, sizeOf<IntVar>().convert()), "set SO_REUSEADDR")
        checked(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()), "bind $host:$port")
        checked(listen(listener, 256), "listen")
        val poller = checked(ktserve_kqueue_open(), "kqueue")
        if (fcntl(poller, F_SETFD, FD_CLOEXEC) < 0) {
            val cause = errno
            close(poller)
            error("set kqueue close-on-exec failed (errno $cause)")
        }
        return KqueueTransport(listener, poller, events, enabled.ptr, connectionLimit)
    } catch (cause: Throwable) {
        close(listener)
        throw cause
    }
}

private fun checked(result: Int, operation: String): Int {
    if (result < 0) error("$operation failed (errno $errno)")
    return result
}

private class KqueueTransport(
    private var listener: Int,
    private var poller: Int,
    private val events: CPointer<ktserve_ready_event>,
    private val enabled: CPointer<IntVar>,
    private val connectionLimit: Int,
) : StreamTransport {
    private val peers = mutableMapOf<ConnectionId, Peer>()
    private val transfers = mutableMapOf<OperationId, Transfer>()
    private val failures = ArrayDeque<OperationId>()
    private var nextConnection = 1L
    private var acceptOperation: OperationId? = null
    private var acceptFailure: TransportFailure? = null

    override fun accept(operation: OperationId): Submission {
        if (listener < 0) return Submission.CLOSED
        if (acceptOperation != null) return Submission.AT_CAPACITY
        acceptOperation = operation
        armAccept(operation)
        return Submission.ACCEPTED
    }

    override fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission =
        submit(connection, operation, bytes, offset, length, writing = false)

    override fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission =
        submit(connection, operation, bytes, offset, length, writing = true)

    private fun submit(
        connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int, writing: Boolean,
    ): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        if (peer.fd < 0) return Submission.CLOSED
        if ((if (writing) peer.write else peer.read) != null) return Submission.AT_CAPACITY
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        val transfer = Transfer(peer, writing, bytes, offset, length)
        if (writing) peer.write = operation else peer.read = operation
        transfers[operation] = transfer
        armTransfer(operation, transfer)
        return Submission.ACCEPTED
    }

    override fun cancel(operation: OperationId) {
        if (acceptOperation == operation && acceptFailure == null) {
            if (listener >= 0) removeWatch(listener, writing = false, operation)
            acceptFailure = TransportFailure.CANCELLED
            return
        }
        val transfer = transfers[operation] ?: return
        if (transfer.failure != null) return
        if (transfer.peer.fd >= 0) removeWatch(transfer.peer.fd, transfer.writing, operation)
        fail(operation, transfer, TransportFailure.CANCELLED)
    }

    override fun closeListener() {
        if (listener < 0) return
        close(listener)
        listener = -1
        if (acceptOperation != null && acceptFailure == null) acceptFailure = TransportFailure.CANCELLED
    }

    override fun closeConnection(connection: ConnectionId) {
        val peer = peers[connection] ?: return
        if (peer.fd < 0) return
        close(peer.fd)
        peer.fd = -1
        peer.read?.let { fail(it, transfers.getValue(it), TransportFailure.CANCELLED) }
        peer.write?.let { fail(it, transfers.getValue(it), TransportFailure.CANCELLED) }
        retirePeer(peer)
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        if (poller < 0) return
        val failure = acceptFailure
        if (failure != null) {
            val operation = checkNotNull(acceptOperation)
            acceptOperation = null
            acceptFailure = null
            completions.failed(null, operation, failure)
        }
        val queued = minOf(BATCH_SIZE, failures.size)
        repeat(queued) {
            if (poller < 0) return
            val operation = failures.removeFirst()
            finish(operation, transfers.getValue(operation), 0, completions)
        }
        if (poller < 0) return

        val wait = if (failure != null || queued > 0) 0 else timeoutMillis
        val count = ktserve_kqueue_poll(poller, events, wait)
        if (count < 0 && errno == EINTR) return
        checked(count, "kevent64 poll")
        repeat(count) { index ->
            if (poller < 0) return
            val event = events[index]
            val operation = OperationId(event.operation.toLong())
            val failed = event.failed != 0
            if (operation == acceptOperation && acceptFailure == null) {
                if (failed) acceptFailure = TransportFailure.IO_FAILURE else acceptReady(operation, completions)
            } else {
                val transfer = transfers[operation] ?: return@repeat
                if (transfer.failure == null) {
                    if (failed) fail(operation, transfer, TransportFailure.IO_FAILURE)
                    else transferReady(operation, transfer, completions)
                }
            }
        }
    }

    private fun acceptReady(operation: OperationId, completions: TransportCompletions) {
        val fd = accept(listener, null, null)
        if (fd < 0) {
            when (errno) {
                EAGAIN, EINTR, ECONNABORTED -> armAccept(operation)
                else -> acceptFailure = TransportFailure.IO_FAILURE
            }
            return
        }
        if (peers.size >= connectionLimit) {
            close(fd)
            completions.admissionRejected()
            armAccept(operation)
            return
        }
        if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0 ||
            fcntl(fd, F_SETFD, FD_CLOEXEC) < 0 ||
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled, sizeOf<IntVar>().convert()) < 0
        ) {
            close(fd)
            armAccept(operation)
            return
        }
        val id = ConnectionId(nextConnection++)
        peers[id] = Peer(id, fd)
        acceptOperation = null
        completions.accepted(operation, id)
    }

    private fun transferReady(operation: OperationId, transfer: Transfer, completions: TransportCompletions) {
        val writing = transfer.writing
        val count = transfer.bytes.usePinned { pinned ->
            val start = pinned.addressOf(transfer.offset)
            if (writing) send(transfer.peer.fd, start, transfer.length.convert(), 0)
            else recv(transfer.peer.fd, start, transfer.length.convert(), 0)
        }
        // An EOF notification can accompany buffered bytes. recv determines progress and EOF.
        if (count < 0 && (errno == EAGAIN || errno == EINTR)) {
            armTransfer(operation, transfer)
            return
        }
        if (count < 0 || writing && count == 0L) transfer.failure = TransportFailure.CONNECTION_LOST
        finish(operation, transfer, count.toInt(), completions)
    }

    private fun finish(operation: OperationId, transfer: Transfer, count: Int, completions: TransportCompletions) {
        val peer = transfer.peer
        val writing = transfer.writing
        transfers.remove(operation)
        if (writing) peer.write = null else peer.read = null
        retirePeer(peer)
        val failure = transfer.failure
        when {
            failure != null -> completions.failed(peer.id, operation, failure)
            writing -> completions.wrote(peer.id, operation, count)
            else -> completions.read(peer.id, operation, count)
        }
    }

    private fun fail(operation: OperationId, transfer: Transfer, reason: TransportFailure) {
        if (transfer.failure != null) return
        transfer.failure = reason
        failures.addLast(operation)
    }

    private fun retirePeer(peer: Peer) {
        if (peer.fd < 0 && peer.read == null && peer.write == null) peers.remove(peer.id)
    }

    private fun armAccept(operation: OperationId) {
        if (watch(listener, writing = false, operation, add = true) < 0) acceptFailure = TransportFailure.IO_FAILURE
    }

    private fun armTransfer(operation: OperationId, transfer: Transfer) {
        if (watch(transfer.peer.fd, transfer.writing, operation, add = true) < 0) {
            fail(operation, transfer, TransportFailure.IO_FAILURE)
        }
    }

    private fun removeWatch(fd: Int, writing: Boolean, operation: OperationId) {
        val result = watch(fd, writing, operation, add = false)
        if (result < 0 && errno != ENOENT) checked(result, "remove kqueue filter")
    }

    private fun watch(fd: Int, writing: Boolean, operation: OperationId, add: Boolean): Int {
        var result: Int
        do {
            result = ktserve_kqueue_watch(poller, fd, if (writing) 1 else 0, operation.value.toULong(), if (add) 1 else 0)
        } while (result < 0 && errno == EINTR)
        return result
    }

    override fun close() {
        if (poller < 0) return
        closeListener()
        for (peer in peers.values) if (peer.fd >= 0) close(peer.fd)
        close(poller)
        poller = -1
        peers.clear()
        transfers.clear()
        failures.clear()
        acceptOperation = null
        acceptFailure = null
    }

    private class Peer(val id: ConnectionId, var fd: Int) {
        var read: OperationId? = null
        var write: OperationId? = null
    }

    private class Transfer(val peer: Peer, val writing: Boolean, val bytes: ByteArray, val offset: Int, val length: Int) {
        var failure: TransportFailure? = null
    }
}
