@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.fixture

import dev.perflab.nativeserve.transport.ConnectionId
import dev.perflab.nativeserve.transport.OperationId
import dev.perflab.nativeserve.transport.StreamTransport
import dev.perflab.nativeserve.transport.Submission
import dev.perflab.nativeserve.transport.TransportCompletions
import dev.perflab.nativeserve.transport.TransportFailure
import dev.perflab.nativeserve.transport.openPlatformTransport
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.htonl
import platform.posix.htons
import platform.posix.memset
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket

internal fun probeTransport(port: Int): Unit = memScoped {
    val transport = openPlatformTransport("127.0.0.1", port, connectionLimit = 2)
    val completions = ProbeCompletions()
    val sockets = mutableListOf<Int>()

    fun connectClient(operation: Long): ConnectionId {
        check(transport.accept(OperationId(operation)) == Submission.ACCEPTED)
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        sockets += fd
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_family = AF_INET.convert()
        address.sin_port = htons(port.toUShort())
        address.sin_addr.s_addr = htonl(0x7f000001U)
        check(connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0)
        transport.await(completions) { completions.accepts.containsKey(OperationId(operation)) }
        return completions.accepts.getValue(OperationId(operation))
    }

    try {
        val cancelledAccept = OperationId(1)
        check(transport.accept(cancelledAccept) == Submission.ACCEPTED)
        check(transport.accept(OperationId(2)) == Submission.AT_CAPACITY)
        transport.cancel(cancelledAccept)
        transport.await(completions) { completions.failures.containsKey(cancelledAccept) }
        check(completions.failures.getValue(cancelledAccept) == (null to TransportFailure.CANCELLED))

        val first = connectClient(3)
        val second = connectClient(4)
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6)
        val firstRead = OperationId(5)
        val secondRead = OperationId(6)
        check(transport.read(first, firstRead, firstBytes, offset = 1, length = 2) == Submission.ACCEPTED)
        check(transport.read(second, secondRead, secondBytes, offset = 1, length = 2) == Submission.ACCEPTED)
        transport.poll(0, completions)
        completions.onRead = { connected -> transport.closeConnection(if (connected == first) second else first) }
        byteArrayOf(9).usePinned { pinned ->
            sockets.forEach { fd -> check(send(fd, pinned.addressOf(0), 1.convert(), 0) == 1L) }
        }

        transport.await(completions) { completions.reads.isNotEmpty() && completions.failures.size == 2 }
        val (read, winningConnection, count) = completions.reads.single()
        check(count == 1)
        val firstWon = winningConnection == first
        check(read == if (firstWon) firstRead else secondRead)
        check(firstBytes.contentEquals(if (firstWon) byteArrayOf(1, 9, 3) else byteArrayOf(1, 2, 3)))
        check(secondBytes.contentEquals(if (firstWon) byteArrayOf(4, 5, 6) else byteArrayOf(4, 9, 6)))
        val losingConnection = if (firstWon) second else first
        val cancelledRead = if (firstWon) secondRead else firstRead
        check(completions.failures.getValue(cancelledRead) == (losingConnection to TransportFailure.CANCELLED))
        transport.poll(0, completions)
        check(completions.reads.size == 1)
        check(transport.read(losingConnection, OperationId(7), ByteArray(1), 0, 1) == Submission.CLOSED)

        val lastRead = OperationId(8)
        check(transport.read(winningConnection, lastRead, ByteArray(1), 0, 1) == Submission.ACCEPTED)
        completions.onRead = { connection ->
            check(transport.write(connection, OperationId(9), byteArrayOf(10), 0, 1) == Submission.ACCEPTED)
            transport.close()
        }
        byteArrayOf(10).usePinned { pinned ->
            val fd = sockets[if (firstWon) 0 else 1]
            check(send(fd, pinned.addressOf(0), 1.convert(), 0) == 1L)
        }
        transport.await(completions) { completions.reads.size == 2 }
        check(completions.reads.last() == Triple(lastRead, winningConnection, 1))
        check(completions.failures.size == 2)
        println("Transport accepted, cancelled and ignored retired stream work")
    } finally {
        transport.close()
        sockets.forEach { close(it) }
    }
}

private fun StreamTransport.await(completions: TransportCompletions, until: () -> Boolean) {
    repeat(20) {
        if (until()) return
        poll(100, completions)
    }
    check(until()) { "Timed out waiting for the expected transport completions" }
}

private class ProbeCompletions : TransportCompletions {
    val accepts = mutableMapOf<OperationId, ConnectionId>()
    val reads = mutableListOf<Triple<OperationId, ConnectionId, Int>>()
    val failures = mutableMapOf<OperationId, Pair<ConnectionId?, TransportFailure>>()
    var onRead: (ConnectionId) -> Unit = {}

    override fun accepted(operation: OperationId, connection: ConnectionId) {
        check(accepts.put(operation, connection) == null)
    }

    override fun read(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        reads += Triple(operation, connection, byteCount)
        onRead(connection)
    }

    override fun wrote(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        error("Unexpected write completion")
    }

    override fun failed(connection: ConnectionId?, operation: OperationId, failure: TransportFailure) {
        check(failures.put(operation, connection to failure) == null)
    }
}
