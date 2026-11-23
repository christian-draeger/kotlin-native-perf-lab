package dev.perflab.reactor.fixture

import dev.perflab.reactor.ServerLimits
import dev.perflab.reactor.serve
import dev.perflab.reactor.text
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

private const val BODY_BYTES = 8 * 1024 * 1024

@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
fun main(args: Array<String>) {
    val limits = ServerLimits(
        connections = 8,
        responseBodyBytes = BODY_BYTES,
        idleMillis = 3_000,
        requestHeadMillis = 500,
    )
    val largeBody = "x".repeat(BODY_BYTES)
    serve(port = args.single().toInt(), limits = limits) {
        get("/health") { text("ok") }
        get("/large") { text(largeBody) }
        get("/too-large") { text(largeBody + "x") }
        get("/heap") {
            GC.collect()
            val bytes = requireNotNull(GC.lastGCInfo?.memoryUsageAfter?.get("heap"))
            text(bytes.totalObjectsSizeBytes.toString())
        }
    }
}
