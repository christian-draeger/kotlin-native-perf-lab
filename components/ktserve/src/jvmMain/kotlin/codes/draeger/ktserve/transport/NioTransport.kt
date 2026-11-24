package codes.draeger.ktserve.transport

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SelectionKey.OP_ACCEPT
import java.nio.channels.SelectionKey.OP_READ
import java.nio.channels.SelectionKey.OP_WRITE
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

private const val BATCH_SIZE = 64

internal fun openNioTransport(host: String, port: Int, connectionLimit: Int): StreamTransport {
    val octets = host.split('.')
    require(octets.size == 4) { "Expected a numeric IPv4 bind address" }
    val address = ByteArray(4) { index ->
        val octet = octets[index]
        val number = octet.toUByteOrNull()
        require(number != null && octet.all { it in '0'..'9' }) { "Expected a numeric IPv4 bind address" }
        number.toByte()
    }
    val selector = Selector.open()
    try {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.INET)
        try {
            listener.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            listener.configureBlocking(false)
            listener.bind(InetSocketAddress(InetAddress.getByAddress(address), port), 256)
            return NioTransport(selector, listener, listener.register(selector, 0), connectionLimit)
        } catch (cause: Throwable) {
            listener.closeSocket()
            throw cause
        }
    } catch (cause: Throwable) {
        selector.closeSocket()
        throw cause
    }
}

private class NioTransport(
    private val selector: Selector,
    private val listener: ServerSocketChannel,
    private val listenerKey: SelectionKey,
    private val connectionLimit: Int,
) : StreamTransport {
    override val signalApplicationProgress: () -> Unit = { selector.wakeup() }
    private val peers = mutableMapOf<ConnectionId, Peer>()
    private val pending = mutableMapOf<OperationId, Transfer>()
    private val failures = ArrayDeque<Transfer>()
    private var acceptOperation: OperationId? = null
    private var acceptFailure: TransportFailure? = null
    private var nextConnection = 1L

    override fun accept(operation: OperationId): Submission {
        if (!listener.isOpen) return Submission.CLOSED
        if (acceptOperation != null) return Submission.AT_CAPACITY
        acceptOperation = operation
        listenerKey.interestOps(OP_ACCEPT)
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
        if (!peer.channel.isOpen) return Submission.CLOSED
        if ((if (writing) peer.write else peer.read) != null) return Submission.AT_CAPACITY
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        val transfer = Transfer(peer, operation, ByteBuffer.wrap(bytes, offset, length), writing)
        if (writing) peer.write = transfer else peer.read = transfer
        pending[operation] = transfer
        updateInterest(peer)
        return Submission.ACCEPTED
    }

    override fun cancel(operation: OperationId) {
        if (acceptOperation == operation && acceptFailure == null) {
            acceptFailure = TransportFailure.CANCELLED
            if (listenerKey.isValid) listenerKey.interestOps(0)
        }
        pending[operation]?.let { fail(it, TransportFailure.CANCELLED) }
    }

    override fun closeListener() {
        listener.closeSocket()
        if (acceptOperation != null && acceptFailure == null) acceptFailure = TransportFailure.CANCELLED
    }

    override fun closeConnection(connection: ConnectionId) {
        val peer = peers[connection] ?: return
        peer.channel.closeSocket()
        peer.read?.let { fail(it, TransportFailure.CANCELLED) }
        peer.write?.let { fail(it, TransportFailure.CANCELLED) }
        retirePeer(peer)
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        if (!selector.isOpen) return
        val failure = acceptFailure
        if (failure != null) {
            val operation = checkNotNull(acceptOperation)
            acceptOperation = null
            acceptFailure = null
            completions.failed(null, operation, failure)
        }
        val queued = minOf(BATCH_SIZE, failures.size)
        repeat(queued) {
            if (!selector.isOpen) return
            finish(failures.removeFirst(), 0, completions)
        }
        if (!selector.isOpen) return

        val selected = selector.selectedKeys()
        if (selected.isEmpty()) {
            if (timeoutMillis == 0 || queued > 0 || failure != null) selector.selectNow()
            else selector.select(timeoutMillis.toLong())
        }
        val iterator = selected.iterator()
        repeat(BATCH_SIZE) {
            if (!selector.isOpen || !iterator.hasNext()) return
            val key = iterator.next()
            iterator.remove()
            if (!key.isValid) return@repeat
            if (key === listenerKey) {
                acceptReady(completions)
            } else {
                val peer = key.attachment() as Peer
                val read = peer.read
                val write = peer.write
                if (key.isReadable && read != null && read.failure == null) transferReady(read, completions)
                if (key.isValid && key.isWritable && write != null && write.failure == null) transferReady(write, completions)
            }
        }
    }

    private fun acceptReady(completions: TransportCompletions) {
        val operation = acceptOperation ?: return
        if (acceptFailure != null) return
        val socket = try {
            listener.accept() ?: return
        } catch (_: IOException) {
            listenerKey.interestOps(0)
            acceptFailure = TransportFailure.IO_FAILURE
            return
        }
        if (peers.size >= connectionLimit) {
            socket.closeSocket()
            return
        }
        val key = try {
            // A small body can follow a separate header write on a reused HTTP connection.
            socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
            socket.configureBlocking(false)
            socket.register(selector, 0)
        } catch (_: IOException) {
            socket.closeSocket()
            return
        }
        val id = ConnectionId(nextConnection++)
        val peer = Peer(id, socket, key)
        peers[id] = peer
        key.attach(peer)
        listenerKey.interestOps(0)
        acceptOperation = null
        completions.accepted(operation, id)
    }

    private fun transferReady(transfer: Transfer, completions: TransportCompletions) {
        val socket = transfer.peer.channel
        val count = try {
            if (transfer.writing) socket.write(transfer.buffer) else socket.read(transfer.buffer)
        } catch (_: IOException) {
            transfer.failure = TransportFailure.CONNECTION_LOST
            finish(transfer, 0, completions)
            return
        }
        if (count != 0) finish(transfer, count.coerceAtLeast(0), completions)
    }

    private fun finish(transfer: Transfer, count: Int, completions: TransportCompletions) {
        val peer = transfer.peer
        pending.remove(transfer.operation)
        if (transfer.writing) peer.write = null else peer.read = null
        updateInterest(peer)
        retirePeer(peer)
        val failure = transfer.failure
        when {
            failure != null -> completions.failed(peer.id, transfer.operation, failure)
            transfer.writing -> completions.wrote(peer.id, transfer.operation, count)
            else -> completions.read(peer.id, transfer.operation, count)
        }
    }

    private fun fail(transfer: Transfer, reason: TransportFailure) {
        if (transfer.failure != null) return
        transfer.failure = reason
        failures.addLast(transfer)
        updateInterest(transfer.peer)
    }

    private fun updateInterest(peer: Peer) {
        if (!peer.key.isValid) return
        var interest = 0
        if (peer.read?.let { it.failure == null } == true) interest = interest or OP_READ
        if (peer.write?.let { it.failure == null } == true) interest = interest or OP_WRITE
        peer.key.interestOps(interest)
    }

    private fun retirePeer(peer: Peer) {
        if (!peer.channel.isOpen && peer.read == null && peer.write == null) peers.remove(peer.id)
    }

    override fun close() {
        if (!selector.isOpen) return
        listener.closeSocket()
        peers.values.forEach { it.channel.closeSocket() }
        selector.closeSocket()
        peers.clear()
        pending.clear()
        failures.clear()
        acceptOperation = null
        acceptFailure = null
    }

    private class Peer(val id: ConnectionId, val channel: SocketChannel, val key: SelectionKey) {
        var read: Transfer? = null
        var write: Transfer? = null
    }

    private class Transfer(val peer: Peer, val operation: OperationId, val buffer: ByteBuffer, val writing: Boolean) {
        var failure: TransportFailure? = null
    }
}

private fun Closeable.closeSocket() {
    try {
        close()
    } catch (_: IOException) {
        // A closed JDK channel retires its selection key and pending work even if close reports an error.
    }
}
