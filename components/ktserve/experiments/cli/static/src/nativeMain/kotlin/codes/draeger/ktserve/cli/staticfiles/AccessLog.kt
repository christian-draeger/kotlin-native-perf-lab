@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package codes.draeger.ktserve.cli.staticfiles

import codes.draeger.ktserve.ExchangeObserver
import codes.draeger.ktserve.ExchangeOutcome
import codes.draeger.ktserve.ServerEvent
import codes.draeger.ktserve.ServerObserver
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.SIG_ERR
import platform.posix.SIG_IGN
import platform.posix.SIGPIPE
import platform.posix.fwrite
import platform.posix.signal
import platform.posix.stderr

internal const val ACCESS_LOG_CAPACITY = 4096
private const val ACCESS_LOG_BATCH_SIZE = 64

internal fun withAccessLog(enabled: Boolean, serve: (ServerObserver?) -> Unit) {
    if (!enabled) {
        serve(null)
        return
    }
    // A closed log pipe must not terminate the CLI while its listener is active.
    val previous = signal(SIGPIPE, SIG_IGN)
    try {
        runAccessLog(serve) { bytes, length ->
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), length.convert(), stderr) }
        }
    } finally {
        if (previous != SIG_ERR) signal(SIGPIPE, previous)
    }
}

/** [writeChunk] borrows the reusable buffer only for the duration of the call. */
internal fun runAccessLog(serve: (ExchangeObserver) -> Unit, writeChunk: (ByteArray, Int) -> Unit) = runBlocking {
    val log = AccessLog()
    val writer = launch(Dispatchers.IO) { log.writeTo(writeChunk) }
    try {
        serve(log)
    } finally {
        log.stop()
        writer.join()
    }
}

private class AccessLog : ExchangeObserver {
    private val loops = AtomicReference<List<LoopAccessLog>>(emptyList())
    private val stopping = AtomicBoolean(false)

    override fun forLoop(): ExchangeObserver {
        val loop = LoopAccessLog()
        while (true) {
            val current = loops.load()
            if (loops.compareAndSet(current, current + loop)) return loop
        }
    }

    override fun onExchange(
        connection: Long, request: Long, method: String?, status: Int, durationNanos: Long, outcome: ExchangeOutcome,
    ): Unit = error("Register a loop before recording access logs")

    fun stop() {
        stopping.store(true)
    }

    suspend fun writeTo(writeChunk: (ByteArray, Int) -> Unit) {
        val batch = AccessLogBatch()
        var dropped = 0L
        while (true) {
            // Observe serve's return before taking the final snapshot of the queues.
            val done = stopping.load()
            val current = loops.load()
            var wrote = false
            for (loop in current) {
                batch.clear()
                loop.drain(batch)
                if (batch.size > 0) {
                    batch.writeTo(writeChunk)
                    wrote = true
                }
                if (loop.isDrained()) {
                    dropped += loop.dropped
                    unregister(loop)
                }
            }
            if (!wrote) {
                if (done) {
                    dropped += loops.load().sumOf { it.dropped }
                    if (dropped > 0) {
                        batch.clear()
                        batch.appendDropped(dropped)
                        batch.writeTo(writeChunk)
                    }
                    return
                }
                delay(1)
            }
        }
    }

    private fun unregister(loop: LoopAccessLog) {
        while (true) {
            val current = loops.load()
            if (loops.compareAndSet(current, current - loop)) return
        }
    }
}

/** One producer owns the write cursor. The writer releases a batch of slots at a time. */
private class LoopAccessLog : ExchangeObserver {
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

    fun drain(batch: AccessLogBatch) {
        val end = minOf(published.load(), nextRead + ACCESS_LOG_BATCH_SIZE)
        if (nextRead == end) return
        while (nextRead < end) {
            val slot = nextRead.toInt() and (ACCESS_LOG_CAPACITY - 1)
            batch.appendExchange(methods[slot], statuses[slot], durations[slot], ExchangeOutcome.entries[outcomes[slot]])
            methods[slot] = null
            nextRead++
        }
        consumed.store(nextRead)
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
