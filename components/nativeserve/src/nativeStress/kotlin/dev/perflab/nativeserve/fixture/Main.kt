package dev.perflab.nativeserve.fixture

import dev.perflab.nativeserve.ResponseSource
import dev.perflab.nativeserve.ServerLimits
import dev.perflab.nativeserve.serve
import dev.perflab.nativeserve.streamResponse
import dev.perflab.nativeserve.text
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

private const val BODY_BYTES = 8 * 1024 * 1024
private const val STREAM_BYTES = BODY_BYTES - 3

@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--transport") {
        probeTransport(args[1].toInt())
        return
    }
    val limits = ServerLimits(
        connections = 8,
        responseBodyBytes = BODY_BYTES,
        idleMillis = 3_000,
        requestHeadMillis = 500,
    )
    val largeBody = "x".repeat(BODY_BYTES)
    var largeRequests = 0
    var openStreams = 0
    var stop = false
    fun source(bytes: Int): ResponseSource {
        openStreams++
        return object : ResponseSource {
            var remaining = bytes

            override fun read(buffer: ByteArray, limit: Int): Int {
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
        get("/large") {
            largeRequests++
            text(largeBody)
        }
        get("/large-count") { text(largeRequests.toString()) }
        get("/too-large") { text(largeBody + "x") }
        get("/stream") { streamResponse(STREAM_BYTES, "application/octet-stream", source(STREAM_BYTES)) }
        get("/truncated-stream") { streamResponse(STREAM_BYTES, "application/octet-stream", source(1)) }
        get("/open-streams") { text(openStreams.toString()) }
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
}
