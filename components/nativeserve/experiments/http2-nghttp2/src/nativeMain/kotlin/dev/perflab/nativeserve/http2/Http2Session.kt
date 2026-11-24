@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.http2

import dev.perflab.nativeserve.http2.bridge.PROBE_CLOSED
import dev.perflab.nativeserve.http2.bridge.PROBE_DATA
import dev.perflab.nativeserve.http2.bridge.PROBE_END
import dev.perflab.nativeserve.http2.bridge.PROBE_HEADER
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
        require(status in 200..599)
        check(!state.responses.containsKey(stream))
        state.responses[stream] = OutboundBody(body)
        if (probe_respond(active(), stream, status, contentType, body.size.convert()) != 0) {
            state.responses.remove(stream)
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
        state.responses.clear()
        stable.dispose()
    }

    private fun active() = checkNotNull(handle)
}

private class OutboundBody(val bytes: ByteArray, var offset: Int = 0)

private class CallbackState(val events: SessionEvents) {
    val responses = mutableMapOf<Int, OutboundBody>()
    var callbackFailure: Throwable? = null

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
            PROBE_DATA -> state.events.data(stream, first.copy(firstLength))
            PROBE_END -> state.events.end(stream)
            PROBE_CLOSED -> {
                state.responses.remove(stream)
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
        val count = minOf(length.toInt(), body.bytes.size - body.offset)
        if (count > 0) {
            body.bytes.usePinned { memcpy(output, it.addressOf(body.offset), count.convert()) }
            body.offset += count
        }
        checkNotNull(done).pointed.value = if (body.offset == body.bytes.size) 1 else 0
        count.toLong()
    } catch (failure: Throwable) {
        state.callbackFailure = failure
        -1L
    }
}

private fun CPointer<UByteVar>?.copy(length: ULong): ByteArray =
    if (length == 0UL) ByteArray(0) else checkNotNull(this).reinterpret<ByteVar>().readBytes(length.toInt())
