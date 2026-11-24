package dev.perflab.nativeserve.fixture

import dev.perflab.nativeserve.transport.ConnectionId
import dev.perflab.nativeserve.transport.OperationId
import dev.perflab.nativeserve.transport.StreamTransport
import dev.perflab.nativeserve.transport.Submission
import dev.perflab.nativeserve.transport.TransportCompletions
import dev.perflab.nativeserve.transport.TransportFailure

internal interface ProbeSocket : AutoCloseable {
    fun sendByte(value: Byte)
}

internal fun probeTransport(transport: StreamTransport, connect: () -> ProbeSocket) {
    val completions = ProbeCompletions()
    val sockets = mutableListOf<ProbeSocket>()

    fun connectClient(operation: Long): ConnectionId {
        check(transport.accept(OperationId(operation)) == Submission.ACCEPTED)
        sockets += connect()
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
        sockets.forEach { it.sendByte(9) }

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
        sockets[if (firstWon) 0 else 1].sendByte(10)
        transport.await(completions) { completions.reads.size == 2 }
        check(completions.reads.last() == Triple(lastRead, winningConnection, 1))
        check(completions.failures.size == 2)
        println("Transport accepted, cancelled and ignored retired stream work")
    } finally {
        sockets.forEach(ProbeSocket::close)
        transport.close()
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
