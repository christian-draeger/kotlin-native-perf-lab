package io.github.christian_draeger.ktserve.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NioWakeupTest {
    @Test
    fun applicationProgressCanSignalBeforeOrDuringTheWait() {
        val transport = openNioTransport("127.0.0.1", port = 0, connectionLimit = 1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val signal = assertNotNull(transport.signalApplicationProgress)
            // A notification before dispatch must make the next blocking poll return.
            signal()
            executor.submit { transport.poll(5_000, noSocketCompletions) }.get(2, TimeUnit.SECONDS)

            val enteredPoll = CountDownLatch(1)
            val pending = executor.submit {
                enteredPoll.countDown()
                transport.poll(5_000, noSocketCompletions)
            }
            assertTrue(enteredPoll.await(2, TimeUnit.SECONDS))
            // The previous notification has been consumed and the selector can sleep again.
            assertFailsWith<TimeoutException> { pending.get(100, TimeUnit.MILLISECONDS) }
            signal()
            pending.get(2, TimeUnit.SECONDS)

            transport.close()
            signal()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            transport.close()
        }
    }

    private val noSocketCompletions = object : TransportCompletions {
        override fun accepted(operation: OperationId, connection: ConnectionId): Nothing = error("Unexpected accept")
        override fun read(connection: ConnectionId, operation: OperationId, byteCount: Int): Nothing = error("Unexpected read")
        override fun wrote(connection: ConnectionId, operation: OperationId, byteCount: Int): Nothing = error("Unexpected write")
        override fun failed(connection: ConnectionId?, operation: OperationId, failure: TransportFailure): Nothing =
            error("Unexpected failure")
    }
}
