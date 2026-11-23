package dev.perflab.nativeserve.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private val acceptId = OperationId(1)
private val readId = OperationId(2)
private val writeId = OperationId(3)
private val nextId = OperationId(4)

class StreamTransportTest {
    private val transport = FakeStreamTransport(capacity = 2)
    private val completions = RecordingCompletions()

    private fun poll(): List<Completion> {
        transport.poll(0, completions)
        return completions.events.toList().also { completions.events.clear() }
    }

    private fun connect(): ConnectionId {
        transport.peerReady()
        assertEquals(Submission.ACCEPTED, transport.accept(acceptId))
        return (poll().single() as Completion.Accepted).connection
    }

    @Test
    fun immediateAcceptAndDelayedPartialTransfers() {
        val connection = connect()
        val bytes = byteArrayOf(10, 11, 12, 13)
        assertEquals(Submission.ACCEPTED, transport.read(connection, readId, bytes, 1, 3))
        assertEquals(emptyList(), poll())
        transport.deliverRead(readId, byteArrayOf(21, 22))
        assertEquals(listOf(Completion.Read(connection, readId, 2)), poll())
        assertContentEquals(byteArrayOf(10, 21, 22, 13), bytes)

        assertEquals(Submission.ACCEPTED, transport.write(connection, writeId, bytes, 1, 3))
        assertContentEquals(byteArrayOf(21, 22), transport.deliverWrite(writeId, 2))
        assertEquals(listOf(Completion.Written(connection, writeId, 2)), poll())
        assertEquals(Submission.ACCEPTED, transport.read(connection, nextId, bytes, 0, 1))
        transport.deliverRead(nextId, byteArrayOf())
        assertEquals(listOf(Completion.Read(connection, nextId, 0)), poll())
    }

    @Test
    fun capacityIncludesUndeliveredOutcomesAndDoesNotBlockCancellation() {
        val connection = connect()
        val bytes = ByteArray(4)
        assertEquals(Submission.ACCEPTED, transport.read(connection, readId, bytes, 0, 2))
        assertEquals(Submission.AT_CAPACITY, transport.read(connection, nextId, bytes, 0, 2))
        assertEquals(Submission.ACCEPTED, transport.write(connection, writeId, bytes, 2, 2))
        transport.cancel(readId)
        assertEquals(Submission.AT_CAPACITY, transport.accept(nextId))
        assertEquals(listOf(Completion.Failed(connection, readId, TransportFailure.CANCELLED)), poll())
        assertEquals(Submission.ACCEPTED, transport.accept(nextId))
        assertEquals(emptyList(), poll())
    }

    @Test
    fun completedWorkWinsCancelRaceAndRetiredOperationsIgnoreLateEvents() {
        val connection = connect()
        val bytes = ByteArray(2)
        assertEquals(Submission.ACCEPTED, transport.read(connection, readId, bytes, 0, 2))
        transport.deliverRead(readId, byteArrayOf(7))
        transport.cancel(readId)
        assertEquals(listOf(Completion.Read(connection, readId, 1)), poll())

        assertEquals(Submission.ACCEPTED, transport.read(connection, nextId, bytes, 1, 1))
        transport.deliverRead(readId, byteArrayOf(8))
        assertEquals(emptyList(), poll())
        transport.closeConnection(connection)
        transport.closeConnection(connection)
        assertEquals(Submission.CLOSED, transport.write(connection, writeId, bytes, 0, 2))
        assertEquals(listOf(Completion.Failed(connection, nextId, TransportFailure.CANCELLED)), poll())
        transport.deliverRead(nextId, byteArrayOf(9))
        assertEquals(emptyList(), poll())
        assertContentEquals(byteArrayOf(7, 0), bytes)
    }

    @Test
    fun shutdownStopsAcceptsAndReleasesPendingOperations() {
        val connection = connect()
        val bytes = ByteArray(1)
        assertEquals(Submission.ACCEPTED, transport.accept(nextId))
        transport.closeListener()
        assertEquals(Submission.CLOSED, transport.accept(OperationId(5)))
        assertEquals(listOf(Completion.Failed(null, nextId, TransportFailure.CANCELLED)), poll())

        assertEquals(Submission.ACCEPTED, transport.read(connection, readId, bytes, 0, 1))
        transport.close()
        transport.close()
        transport.deliverRead(readId, byteArrayOf(1))
        assertContentEquals(byteArrayOf(0), bytes)
        assertEquals(emptyList(), poll())
        assertEquals(Submission.CLOSED, transport.write(connection, writeId, bytes, 0, 1))
    }

    @Test
    fun anAcceptThatWinsTheCloseRaceStillTransfersOwnership() {
        assertEquals(Submission.ACCEPTED, transport.accept(acceptId))
        assertEquals(emptyList(), poll())
        transport.peerReady()
        transport.closeListener()
        val connection = (poll().single() as Completion.Accepted).connection
        transport.closeConnection(connection)
        assertEquals(Submission.CLOSED, transport.read(connection, readId, ByteArray(1), 0, 1))
        assertEquals(emptyList(), poll())
    }
}

private sealed interface Completion {
    data class Accepted(val operation: OperationId, val connection: ConnectionId) : Completion
    data class Read(val connection: ConnectionId, val operation: OperationId, val count: Int) : Completion
    data class Written(val connection: ConnectionId, val operation: OperationId, val count: Int) : Completion
    data class Failed(val connection: ConnectionId?, val operation: OperationId, val failure: TransportFailure) : Completion
}

private class RecordingCompletions : TransportCompletions {
    val events = mutableListOf<Completion>()

    override fun accepted(operation: OperationId, connection: ConnectionId) {
        events += Completion.Accepted(operation, connection)
    }

    override fun read(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        events += Completion.Read(connection, operation, byteCount)
    }

    override fun wrote(connection: ConnectionId, operation: OperationId, byteCount: Int) {
        events += Completion.Written(connection, operation, byteCount)
    }

    override fun failed(connection: ConnectionId?, operation: OperationId, failure: TransportFailure) {
        events += Completion.Failed(connection, operation, failure)
    }
}

private class FakeStreamTransport(private val capacity: Int) : StreamTransport {
    private enum class Kind { ACCEPT, READ, WRITE }

    private data class Transfer(val connection: ConnectionId, val bytes: ByteArray, val offset: Int, val length: Int)
    private class Pending(val kind: Kind, val transfer: Transfer? = null, var completion: Completion? = null)

    private val pending = mutableMapOf<OperationId, Pending>()
    private val connections = mutableSetOf<ConnectionId>()
    private var nextConnection = 1L
    private var listening = true
    private var peerWaiting = false

    fun peerReady() {
        val accept = pending.entries.firstOrNull { (_, slot) -> slot.kind == Kind.ACCEPT && slot.completion == null }
        if (accept == null) {
            peerWaiting = true
        } else {
            accept.value.completion = Completion.Accepted(accept.key, ConnectionId(nextConnection++))
        }
    }

    override fun accept(operation: OperationId): Submission {
        if (!listening) return Submission.CLOSED
        if (pending.size == capacity) return Submission.AT_CAPACITY
        val slot = Pending(Kind.ACCEPT)
        if (peerWaiting) {
            peerWaiting = false
            slot.completion = Completion.Accepted(operation, ConnectionId(nextConnection++))
        }
        pending[operation] = slot
        return Submission.ACCEPTED
    }

    override fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int) =
        submit(operation, Kind.READ, Transfer(connection, bytes, offset, length))

    override fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int) =
        submit(operation, Kind.WRITE, Transfer(connection, bytes, offset, length))

    private fun submit(operation: OperationId, kind: Kind, transfer: Transfer): Submission {
        if (transfer.connection !in connections) return Submission.CLOSED
        if (pending.size == capacity || pending.values.any { it.kind == kind && it.transfer?.connection == transfer.connection }) {
            return Submission.AT_CAPACITY
        }
        pending[operation] = Pending(kind, transfer)
        return Submission.ACCEPTED
    }

    fun deliverRead(operation: OperationId, data: ByteArray) {
        val slot = pending[operation] ?: return
        if (slot.completion != null) return
        val transfer = checkNotNull(slot.transfer)
        check(slot.kind == Kind.READ && data.size <= transfer.length)
        data.copyInto(transfer.bytes, transfer.offset)
        slot.completion = Completion.Read(transfer.connection, operation, data.size)
    }

    fun deliverWrite(operation: OperationId, count: Int): ByteArray {
        val slot = pending.getValue(operation)
        val transfer = checkNotNull(slot.transfer)
        check(slot.kind == Kind.WRITE && slot.completion == null && count in 1..transfer.length)
        slot.completion = Completion.Written(transfer.connection, operation, count)
        return transfer.bytes.copyOfRange(transfer.offset, transfer.offset + count)
    }

    override fun cancel(operation: OperationId) {
        val slot = pending[operation] ?: return
        if (slot.completion == null) slot.completion = Completion.Failed(slot.transfer?.connection, operation, TransportFailure.CANCELLED)
    }

    override fun closeListener() {
        listening = false
        pending.forEach { (id, slot) -> if (slot.kind == Kind.ACCEPT) cancel(id) }
    }

    override fun closeConnection(connection: ConnectionId) {
        connections.remove(connection)
        pending.forEach { (id, slot) -> if (slot.transfer?.connection == connection) cancel(id) }
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        val ready = pending.entries.mapNotNull { (id, slot) -> slot.completion?.let { id to it } }
        for ((id, completion) in ready) {
            if (pending.remove(id) == null) continue
            when (completion) {
                is Completion.Accepted -> {
                    connections += completion.connection
                    completions.accepted(id, completion.connection)
                }
                is Completion.Read -> completions.read(completion.connection, id, completion.count)
                is Completion.Written -> completions.wrote(completion.connection, id, completion.count)
                is Completion.Failed -> completions.failed(completion.connection, id, completion.failure)
            }
        }
    }

    override fun close() {
        listening = false
        pending.clear()
        connections.clear()
    }
}
