@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package codes.draeger.ktserve.accesslog

import codes.draeger.ktserve.ExchangeObserver
import codes.draeger.ktserve.ExchangeOutcome
import codes.draeger.ktserve.ServerEvent
import codes.draeger.ktserve.ServerExtension
import codes.draeger.ktserve.extension
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Records emitted by the access-log writer. */
sealed interface AccessLogEvent {
    data class Exchange(
        val method: String?,
        val status: Int?,
        val durationNanos: Long,
        val outcome: ExchangeOutcome,
    ) : AccessLogEvent

    /** Total dropped records reported when the server stops. */
    data class Dropped(val count: Long) : AccessLogEvent
}

/** Write batched access records to stderr using the standard ktServe format. */
fun accessLog(): ServerExtension = createAccessLog {
    BatchedAccessLogWriter(::writeAccessLogToStderr)
}

/**
 * Deliver structured records on a background writer. The sink controls their format and destination.
 * A sink exception stops export for this listener. Serving continues with bounded capture buffers.
 */
fun accessLog(sink: (AccessLogEvent) -> Unit): ServerExtension = createAccessLog {
    EventAccessLogWriter(sink)
}

private fun createAccessLog(createWriter: () -> AccessLogWriter): ServerExtension =
    extension("codes.draeger.ktserve.access-log") {
        val logger = AccessLog(createWriter())
        observe(logger)
        onServerStart(logger::start)
    }

internal expect fun writeAccessLogToStderr(bytes: ByteArray, length: Int)

internal const val ACCESS_LOG_CAPACITY = 4096
private const val ACCESS_LOG_BATCH_SIZE = 64
private const val ACCESS_LOG_LIGHT_POLL_MS = 4L

internal class AccessLog(private val writer: AccessLogWriter) : ExchangeObserver {
    private val loops = AtomicReference<List<LoopAccessLog>?>(emptyList())
    private val stopping = AtomicBoolean(false)

    fun start(): AutoCloseable {
        val scope = CoroutineScope(Dispatchers.IO)
        val writing = scope.launch {
            try {
                writeTo()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed sink must not interrupt serving or hold up shutdown.
            } finally {
                // Retired loops must remain collectible even when the writer stops early.
                loops.store(null)
            }
        }
        return AutoCloseable {
            stopping.store(true)
            try {
                runBlocking { writing.join() }
            } finally {
                scope.cancel()
            }
        }
    }

    override fun forLoop(): ExchangeObserver {
        var current = loops.load() ?: return inactiveAccessLog
        val loop = LoopAccessLog()
        while (true) {
            if (loops.compareAndSet(current, current + loop)) return loop
            current = loops.load() ?: return inactiveAccessLog
        }
    }

    override fun onExchange(
        connection: Long, request: Long, method: String?, status: Int, durationNanos: Long, outcome: ExchangeOutcome,
    ): Unit = error("Register a loop before recording access logs")

    private suspend fun writeTo() {
        when (val output = writer) {
            is BatchedAccessLogWriter -> writeTo(output::drain, output::dropped)
            is EventAccessLogWriter -> writeTo(output::drain, output::dropped)
        }
    }

    private suspend inline fun writeTo(drain: (LoopAccessLog) -> Int, reportDropped: (Long) -> Unit) {
        var dropped = 0L
        var busy = false
        while (true) {
            // Observe serve's return before taking the final snapshot of the queues.
            val done = stopping.load()
            val current = loops.load() ?: return
            var wrote = false
            for (loop in current) {
                val count = drain(loop)
                wrote = wrote || count > 0
                busy = busy || count == ACCESS_LOG_BATCH_SIZE
                if (loop.isDrained()) {
                    dropped += loop.dropped
                    unregister(loop)
                }
            }
            if (!wrote) {
                if (done) {
                    dropped += loops.load().orEmpty().sumOf { it.dropped }
                    if (dropped > 0) reportDropped(dropped)
                    return
                }
                // Give sparse records time to form a batch. Poll sooner after a full batch.
                delay(if (busy) 1L else ACCESS_LOG_LIGHT_POLL_MS)
                busy = false
            }
        }
    }

    private fun unregister(loop: LoopAccessLog) {
        while (true) {
            val current = loops.load() ?: return
            if (loops.compareAndSet(current, current - loop)) return
        }
    }
}

private val inactiveAccessLog = ExchangeObserver { _, _, _, _, _, _ -> }

internal sealed interface AccessLogWriter

/** [writeChunk] borrows the reusable buffer for the duration of the call. */
internal class BatchedAccessLogWriter(private val writeChunk: (ByteArray, Int) -> Unit) : AccessLogWriter {
    private val batch = AccessLogBatch()

    fun drain(loop: LoopAccessLog): Int {
        batch.clear()
        val count = loop.drain(batch::appendExchange)
        if (count > 0) batch.writeTo(writeChunk)
        return count
    }

    fun dropped(count: Long) {
        batch.clear()
        batch.appendDropped(count)
        batch.writeTo(writeChunk)
    }
}

internal class EventAccessLogWriter(private val sink: (AccessLogEvent) -> Unit) : AccessLogWriter {
    fun drain(loop: LoopAccessLog): Int = loop.drain { method, status, nanos, outcome ->
        sink(AccessLogEvent.Exchange(method, status.takeIf { it != 0 }, nanos, outcome))
    }

    fun dropped(count: Long) = sink(AccessLogEvent.Dropped(count))
}

/** One producer owns the write cursor. The writer releases a batch of slots at a time. */
internal class LoopAccessLog : ExchangeObserver {
    private val methods = arrayOfNulls<String>(ACCESS_LOG_CAPACITY)
    private val statuses = IntArray(ACCESS_LOG_CAPACITY)
    private val durations = LongArray(ACCESS_LOG_CAPACITY)
    private val outcomes = IntArray(ACCESS_LOG_CAPACITY)
    private val published = AtomicLong(0)
    private val consumed = AtomicLong(0)
    private val stopped = AtomicBoolean(false)
    private var nextWrite = 0L
    private var cachedRead = 0L
    private var nextRead = 0L
    // The writer reads this only after observing STOPPED or the end of serve.
    var dropped = 0L
        private set

    override fun onExchange(
        connection: Long, request: Long, method: String?, status: Int, durationNanos: Long, outcome: ExchangeOutcome,
    ) {
        if (nextWrite - cachedRead == ACCESS_LOG_CAPACITY.toLong()) {
            cachedRead = consumed.load()
            if (nextWrite - cachedRead == ACCESS_LOG_CAPACITY.toLong()) {
                dropped++
                return
            }
        }
        val slot = nextWrite.toInt() and (ACCESS_LOG_CAPACITY - 1)
        methods[slot] = method
        statuses[slot] = status
        durations[slot] = durationNanos
        outcomes[slot] = outcome.ordinal
        published.store(++nextWrite)
    }

    override fun onEvent(event: ServerEvent) {
        if (event == ServerEvent.Lifecycle.STOPPED) stopped.store(true)
    }

    inline fun drain(append: (String?, Int, Long, ExchangeOutcome) -> Unit): Int {
        val start = nextRead
        val end = minOf(published.load(), start + ACCESS_LOG_BATCH_SIZE)
        if (start == end) return 0
        while (nextRead < end) {
            val slot = nextRead.toInt() and (ACCESS_LOG_CAPACITY - 1)
            append(methods[slot], statuses[slot], durations[slot], ExchangeOutcome.entries[outcomes[slot]])
            methods[slot] = null
            nextRead++
        }
        consumed.store(nextRead)
        return (end - start).toInt()
    }

    fun isDrained(): Boolean = stopped.load() && nextRead == published.load()
}

private class AccessLogBatch {
    private val methodField = "event=exchange method=".encodeToByteArray()
    private val statusField = " status=".encodeToByteArray()
    private val durationField = " duration_ns=".encodeToByteArray()
    private val outcomeField = " outcome=".encodeToByteArray()
    private val outcomes = ExchangeOutcome.entries.map { it.name.encodeToByteArray() }
    private val droppedField = "event=access_log_dropped count=".encodeToByteArray()
    private val digits = ByteArray(20)
    private var bytes = ByteArray(4096)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun writeTo(write: (ByteArray, Int) -> Unit) = write(bytes, size)

    fun appendExchange(method: String?, status: Int, durationNanos: Long, outcome: ExchangeOutcome) {
        append(methodField)
        if (method == null) appendAscii('-') else appendMethod(method)
        append(statusField)
        if (status == 0) appendAscii('-') else appendNumber(status.toLong())
        append(durationField)
        appendNumber(durationNanos)
        append(outcomeField)
        append(outcomes[outcome.ordinal])
        appendAscii('\n')
    }

    fun appendDropped(count: Long) {
        append(droppedField)
        appendNumber(count)
        appendAscii('\n')
    }

    private fun appendMethod(method: String) {
        ensureCapacity(method.length)
        val start = size
        for (character in method) {
            if (character.code > 127) {
                size = start
                append(method.encodeToByteArray())
                return
            }
            bytes[size++] = character.code.toByte()
        }
    }

    private fun appendAscii(character: Char) {
        ensureCapacity(1)
        bytes[size++] = character.code.toByte()
    }

    private fun appendNumber(number: Long) {
        // Accumulate negative digits so Long.MIN_VALUE needs no special case.
        var remaining = if (number > 0) -number else number
        var start = digits.size
        do {
            digits[--start] = ('0'.code - (remaining % 10).toInt()).toByte()
            remaining /= 10
        } while (remaining != 0L)
        if (number < 0) digits[--start] = '-'.code.toByte()
        append(digits, start)
    }

    private fun append(value: ByteArray, start: Int = 0) {
        val length = value.size - start
        ensureCapacity(length)
        value.copyInto(bytes, size, start)
        size += length
    }

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        if (required > bytes.size) bytes = bytes.copyOf(maxOf(required, bytes.size * 2))
    }
}
