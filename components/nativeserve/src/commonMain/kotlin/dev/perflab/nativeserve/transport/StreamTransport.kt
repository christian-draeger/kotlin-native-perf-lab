package dev.perflab.nativeserve.transport

import kotlin.jvm.JvmInline

@JvmInline
internal value class ConnectionId(val value: Long)

@JvmInline
internal value class OperationId(val value: Long)

internal enum class Submission { ACCEPTED, AT_CAPACITY, CLOSED }

internal enum class TransportFailure { CANCELLED, CONNECTION_LOST, IO_FAILURE }

internal interface TransportCompletions {
    fun accepted(operation: OperationId, connection: ConnectionId)
    fun read(operation: OperationId, byteCount: Int)
    fun wrote(operation: OperationId, byteCount: Int)
    fun failed(operation: OperationId, failure: TransportFailure)
}

/**
 * A bound stream listener and its connections, owned by one event-loop thread.
 * Accepted submissions lease their buffer ranges until a terminal callback or [close].
 * Rejected submissions never acquire a lease or produce a callback.
 */
internal interface StreamTransport {
    fun accept(operation: OperationId): Submission
    fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission
    fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission

    /** Requests cancellation. The operation can still complete naturally. */
    fun cancel(operation: OperationId)

    fun closeListener()
    fun closeConnection(connection: ConnectionId)

    /** Delivers terminal callbacks serially. A timeout of zero performs a nonblocking poll. */
    fun poll(timeoutMillis: Int, completions: TransportCompletions)

    /** Discards undelivered outcomes and releases all buffers and handles before returning. */
    fun close()
}
