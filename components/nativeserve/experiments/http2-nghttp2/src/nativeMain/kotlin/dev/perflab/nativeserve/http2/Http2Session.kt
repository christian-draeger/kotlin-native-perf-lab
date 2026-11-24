@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.http2

import dev.perflab.nativeserve.ResponseSource
import dev.perflab.nativeserve.http2.bridge.PROBE_CLOSED
import dev.perflab.nativeserve.http2.bridge.PROBE_DATA
import dev.perflab.nativeserve.http2.bridge.PROBE_END
import dev.perflab.nativeserve.http2.bridge.PROBE_HEADER
import dev.perflab.nativeserve.http2.bridge.PROBE_HEADERS_END
import dev.perflab.nativeserve.http2.bridge.probe_cancel
import dev.perflab.nativeserve.http2.bridge.probe_close
import dev.perflab.nativeserve.http2.bridge.probe_consume
import dev.perflab.nativeserve.http2.bridge.probe_get
import dev.perflab.nativeserve.http2.bridge.probe_goaway
import dev.perflab.nativeserve.http2.bridge.probe_input
import dev.perflab.nativeserve.http2.bridge.probe_open
import dev.perflab.nativeserve.http2.bridge.probe_output
import dev.perflab.nativeserve.http2.bridge.probe_respond
import dev.perflab.nativeserve.http2.bridge.probe_session
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy

internal interface SessionEvents {
    fun header(stream: Int, name: String, value: String): Boolean = true
    fun headersEnd(stream: Int) {}
    fun data(stream: Int, bytes: ByteArray) {}
    fun end(stream: Int) {}
    fun closed(stream: Int, code: UInt) {}
}

internal class Http2Session(server: Boolean, events: SessionEvents) : AutoCloseable {
    private val state = CallbackState(events)
    private val stable = StableRef.create(state)
    private var handle: CPointer<probe_session>? =
        probe_open(if (server) 1 else 0, stable.asCPointer(), staticCFunction(::onEvent), staticCFunction(::onRead))
        ?: run {
            stable.dispose()
            error("Could not create nghttp2 session")
        }

    fun get(path: String): Int = probe_get(active(), path).also { check(it > 0) }

    fun respond(stream: Int, status: Int, body: ByteArray, contentType: String? = "text/plain") {
        respond(stream, status, contentType, BufferedBody(body))
    }

    fun respond(stream: Int, status: Int, contentType: String?, source: ResponseSource, length: Int?, limit: Int) {
        respond(stream, status, contentType, StreamingBody(source, length, limit))
    }

    private fun respond(stream: Int, status: Int, contentType: String?, body: OutboundBody) {
        require(status in 200..599)
        check(!state.responses.containsKey(stream))
        state.responses[stream] = body
        val length = body.length?.toLong() ?: -1L
        if (probe_respond(active(), stream, status, contentType, length, if (length == 0L) 0 else 1) != 0) {
            state.closeResponse(stream)
            error("Could not submit nghttp2 response")
        }
    }

    fun addCredit(stream: Int, bytes: Int) {
        require(bytes > 0)
        check(probe_consume(active(), stream, bytes.convert()) == 0)
    }

    fun cancel(stream: Int) {
        check(probe_cancel(active(), stream) == 0)
    }

    fun beginDrain() {
        check(probe_goaway(active()) == 0)
    }

    fun receive(bytes: ByteArray, start: Int, count: Int) {
        require(count > 0 && start >= 0 && start <= bytes.size - count)
        val used = bytes.usePinned { probe_input(active(), it.addressOf(start).reinterpret(), count.convert()) }
        state.checkCallback()
        check(used == count.toLong()) { "nghttp2 consumed $used of $count supplied bytes" }
    }

    fun output(bytes: ByteArray): Int {
        require(bytes.isNotEmpty())
        val count = bytes.usePinned { probe_output(active(), it.addressOf(0).reinterpret(), bytes.size.convert()) }
        state.checkCallback()
        check(count >= 0) { "nghttp2 output error $count" }
        return count.toInt()
    }

    override fun close() {
        val current = handle ?: return
        handle = null
        probe_close(current)
        state.responses.keys.toList().forEach(state::closeResponse)
        stable.dispose()
    }

    private fun active() = checkNotNull(handle)
}

private interface OutboundBody : AutoCloseable {
    val length: Int?
    val done: Boolean
    fun read(output: CPointer<UByteVar>?, limit: Int): Int
    override fun close() {}
}

private class BufferedBody(private val bytes: ByteArray) : OutboundBody {
    private var offset = 0
    override val length get() = bytes.size
    override val done get() = offset == bytes.size

    override fun read(output: CPointer<UByteVar>?, limit: Int): Int {
        val count = minOf(limit, bytes.size - offset)
        if (count > 0) bytes.usePinned { memcpy(output, it.addressOf(offset), count.convert()) }
        offset += count
        return count
    }
}

private class StreamingBody(private val source: ResponseSource, override val length: Int?, limit: Int) : OutboundBody {
    private var remaining = length ?: limit
    private val buffer = ByteArray(minOf(16 * 1024, remaining))
    override var done = length == 0
        private set

    override fun read(output: CPointer<UByteVar>?, limit: Int): Int {
        val offered = minOf(limit, buffer.size, remaining).coerceAtLeast(1)
        val count = source.read(buffer, offered)
        if (length == null && count == -1) {
            done = true
            return 0
        }
        check(count in 1..offered && count <= remaining) { "Response source exceeded its framing or byte limit" }
        buffer.usePinned { memcpy(output, it.addressOf(0), count.convert()) }
        remaining -= count
        done = length != null && remaining == 0
        return count
    }

    override fun close() = source.close()
}

private class CallbackState(val events: SessionEvents) {
    val responses = mutableMapOf<Int, OutboundBody>()
    var callbackFailure: Throwable? = null

    fun closeResponse(stream: Int) {
        try {
            responses.remove(stream)?.close()
        } catch (_: Exception) {
            // The source is already detached from the protocol state.
        }
    }

    fun checkCallback() {
        callbackFailure?.let { throw IllegalStateException("HTTP/2 callback failed", it) }
    }
}

private fun onEvent(
    context: COpaquePointer?, kind: Int, stream: Int,
    first: CPointer<UByteVar>?, firstLength: ULong,
    second: CPointer<UByteVar>?, secondLength: ULong,
    error: UInt,
): Int {
    val state = context?.asStableRef<CallbackState>()?.get() ?: return -1
    return try {
        when (kind) {
            PROBE_HEADER -> if (!state.events.header(
                    stream, first.copy(firstLength).decodeToString(), second.copy(secondLength).decodeToString(),
                )) return -1
            PROBE_HEADERS_END -> state.events.headersEnd(stream)
            PROBE_DATA -> state.events.data(stream, first.copy(firstLength))
            PROBE_END -> state.events.end(stream)
            PROBE_CLOSED -> {
                state.closeResponse(stream)
                state.events.closed(stream, error)
            }
        }
        0
    } catch (failure: Throwable) {
        state.callbackFailure = failure
        -1
    }
}

private fun onRead(
    context: COpaquePointer?, stream: Int, output: CPointer<UByteVar>?, length: ULong, done: CPointer<IntVar>?,
): Long {
    val state = context?.asStableRef<CallbackState>()?.get() ?: return -1L
    return try {
        val body = state.responses.getValue(stream)
        val count = body.read(output, length.toInt())
        checkNotNull(done).pointed.value = if (body.done) 1 else 0
        count.toLong()
    } catch (_: Throwable) {
        // nghttp2 converts the read failure into a reset for this stream.
        -1L
    }
}

private fun CPointer<UByteVar>?.copy(length: ULong): ByteArray =
    if (length == 0UL) ByteArray(0) else checkNotNull(this).reinterpret<ByteVar>().readBytes(length.toInt())
