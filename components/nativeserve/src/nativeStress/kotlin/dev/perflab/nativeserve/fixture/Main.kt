package dev.perflab.nativeserve.fixture

import dev.perflab.nativeserve.RequestBodySink
import dev.perflab.nativeserve.Response
import dev.perflab.nativeserve.ResponseSource
import dev.perflab.nativeserve.ServerLimits
import dev.perflab.nativeserve.serve
import dev.perflab.nativeserve.streamResponse
import dev.perflab.nativeserve.text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

private const val BODY_BYTES = 8 * 1024 * 1024
private const val STREAM_BYTES = BODY_BYTES - 3

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun main(args: Array<String>) = runFixture(args, ::probeTransport)

@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class, ExperimentalAtomicApi::class)
internal fun runFixture(args: Array<String>, transportProbe: (Int) -> Unit) {
    if (args.firstOrNull() == "--transport") {
        transportProbe(args[1].toInt())
        return
    }
    val limits = ServerLimits(
        connections = 8,
        responseBodyBytes = BODY_BYTES,
        idleMillis = 3_000,
        requestHeadMillis = 500,
        requestBodyMillis = 1_000,
    )
    val largeBody = "x".repeat(BODY_BYTES)
    var largeRequests = 0
    var openStreams = 0
    var openRequests = 0
    var stop = false
    val openHandlers = AtomicInt(0)
    val ready = CompletableDeferred<Unit>()
    fun source(bytes: Int): ResponseSource {
        openStreams++
        return object : ResponseSource {
            var remaining = bytes

            override fun read(buffer: ByteArray, limit: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(remaining, limit)
                buffer.fill('s'.code.toByte(), toIndex = count)
                remaining -= count
                return count
            }

            override fun close() {
                openStreams--
            }
        }
    }
    serve(port = args.single().toInt(), limits = limits, stopRequested = { stop }) {
        get("/health") { text("ok") }
        get("/open-handlers") { text(openHandlers.load().toString()) }
        get("/complete-handler") {
            ready.complete(Unit)
            text("ok")
        }
        getSuspending("/suspend/{kind}") { request ->
            openHandlers.addAndFetch(1)
            try {
                if (request.pathParameter("kind") == "cancel") awaitCancellation()
                ready.await()
                text("resumed")
            } finally {
                openHandlers.addAndFetch(-1)
            }
        }
        get("/large") {
            largeRequests++
            text(largeBody)
        }
        get("/large-count") { text(largeRequests.toString()) }
        get("/too-large") { text(largeBody + "x") }
        get("/stream") { streamResponse(STREAM_BYTES, "application/octet-stream", source(STREAM_BYTES)) }
        get("/chunks") { streamResponse("application/octet-stream", source(32 * 1024 + 3)) }
        get("/truncated-stream") { streamResponse(STREAM_BYTES, "application/octet-stream", source(1)) }
        get("/open-streams") { text(openStreams.toString()) }
        get("/open-requests") { text(openRequests.toString()) }
        postStreaming("/upload") {
            openRequests++
            object : RequestBodySink {
                var bytes = 0

                override fun accept(buffer: ByteArray, length: Int) {
                    bytes += length
                }

                override fun complete(): Response = text(bytes.toString())

                override fun close() {
                    openRequests--
                }
            }
        }
        get("/stop") {
            stop = true
            text("ok")
        }
        get("/heap") {
            GC.collect()
            val bytes = requireNotNull(GC.lastGCInfo?.memoryUsageAfter?.get("heap"))
            text(bytes.totalObjectsSizeBytes.toString())
        }
    }
    check(openStreams == 0) { "Shutdown left $openStreams response sources open" }
    check(openRequests == 0) { "Shutdown left $openRequests request sinks open" }
}
