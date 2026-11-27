package codes.draeger.ktserve.cli.staticfiles

import codes.draeger.ktserve.ExchangeObserver
import codes.draeger.ktserve.ExchangeOutcome
import codes.draeger.ktserve.ServerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AccessLogTest {
    private val exchangeLine = "event=exchange method=GET status=200 duration_ns=123 outcome=COMPLETED"

    @Test
    fun disabledLoggerSuppliesNoObserver() {
        withAccessLog(false) { observer -> assertNull(observer) }
    }

    @Test
    fun writesOnlyExchangesAndDrainsTheQueueOnReturn() {
        val chunks = mutableListOf<String>()
        runAccessLog(serve = { observer ->
            val loop = observer.forLoop()
            loop.onEvent(ServerEvent.Lifecycle.READY)
            loop.record()
            loop.onEvent(ServerEvent.AdmissionRejected)
            loop.record(method = null, status = 0)
            loop.onEvent(ServerEvent.Lifecycle.STOPPED)
        }, writeChunk = { bytes, length -> chunks.add(bytes.decodeToString(endIndex = length)) })

        assertEquals(listOf(exchangeLine, "event=exchange method=- status=- duration_ns=123 outcome=COMPLETED"), chunks.lines())
    }

    @Test
    fun dropsOverflowWhileTheWriterIsBlocked() {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val chunks = mutableListOf<String>()
        runAccessLog(serve = { observer ->
            val loop = observer.forLoop()
            loop.record()
            try {
                runBlocking { withTimeout(5_000) { writing.await() } }
                repeat(ACCESS_LOG_CAPACITY + 4) { loop.record() }
                loop.onEvent(ServerEvent.Lifecycle.STOPPED)
            } finally {
                release.complete(Unit)
            }
        }, writeChunk = { bytes, length ->
            writing.complete(Unit)
            runBlocking { release.await() }
            chunks.add(bytes.decodeToString(endIndex = length))
        })

        assertEquals(List(ACCESS_LOG_CAPACITY + 1) { exchangeLine } + "event=access_log_dropped count=4", chunks.lines())
        assertEquals(listOf(1) + List(ACCESS_LOG_CAPACITY / 64) { 64 } + 1, chunks.map { chunk -> chunk.count { it == '\n' } })
    }

    @Test
    fun keepsPerLoopOrderWhenSlotsWrapAndLoopsRetire() {
        val firstBatchWritten = CompletableDeferred<Unit>()
        val chunks = mutableListOf<String>()
        val count = ACCESS_LOG_CAPACITY / 2 + 16
        var linesWritten = 0
        runAccessLog(serve = { observer ->
            val first = observer.forLoop()
            repeat(count) { first.record(duration = it.toLong()) }
            runBlocking { withTimeout(5_000) { firstBatchWritten.await() } }

            val second = observer.forLoop()
            repeat(count) { index ->
                first.record(duration = (index + count).toLong())
                second.record(method = "HEAD", duration = index.toLong())
            }
            second.onEvent(ServerEvent.Lifecycle.STOPPED)
            first.onEvent(ServerEvent.Lifecycle.STOPPED)
        }, writeChunk = { bytes, length ->
            val chunk = bytes.decodeToString(endIndex = length)
            chunks.add(chunk)
            linesWritten += chunk.count { it == '\n' }
            if (linesWritten >= count) firstBatchWritten.complete(Unit)
        })

        val lines = chunks.lines()
        assertEquals((0 until count * 2).toList(), lines.durations("GET"))
        assertEquals((0 until count).toList(), lines.durations("HEAD"))
        assertEquals(count * 3, lines.size)
    }

    @Test
    fun growsTheBufferForUtf8AndPreservesSignedDecimalValues() {
        val method = "GET-" + "é".repeat(4096)
        val chunks = mutableListOf<String>()
        runAccessLog(serve = { observer ->
            val loop = observer.forLoop()
            loop.record(method = method, duration = Long.MIN_VALUE)
            loop.record(duration = Long.MAX_VALUE)
            loop.onEvent(ServerEvent.Lifecycle.STOPPED)
        }, writeChunk = { bytes, length -> chunks.add(bytes.decodeToString(endIndex = length)) })

        assertEquals(listOf(
            "event=exchange method=$method status=200 duration_ns=${Long.MIN_VALUE} outcome=COMPLETED",
            "event=exchange method=GET status=200 duration_ns=${Long.MAX_VALUE} outcome=COMPLETED",
        ), chunks.lines())
    }

    private fun ExchangeObserver.record(method: String? = "GET", status: Int = 200, duration: Long = 123L) {
        onExchange(0, 0, method, status, duration, ExchangeOutcome.COMPLETED)
    }

    private fun List<String>.durations(method: String): List<Int> =
        filter { "method=$method " in it }.map { it.substringAfter("duration_ns=").substringBefore(' ').toInt() }

    private fun List<String>.lines(): List<String> {
        assertTrue(all { it.endsWith('\n') })
        return joinToString("").lines().dropLast(1)
    }
}
