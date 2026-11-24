package dev.perflab.nativeserve.example

import dev.perflab.nativeserve.serveEpoll
import dev.perflab.nativeserve.signals.REACTOR_INSTALL_SIGNALS
import dev.perflab.nativeserve.signals.REACTOR_READ_STOP
import dev.perflab.nativeserve.signals.REACTOR_RESTORE_SIGNALS
import dev.perflab.nativeserve.signals.reactor_signals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
    val workers = getenv("NATIVESERVE_WORKERS")?.toKString()?.toInt() ?: 1
    val handlerMode = getenv("NATIVESERVE_BENCHMARK_HANDLER")?.toKString() ?: "inline"
    require(handlerMode in listOf("inline", "dispatcher", "eventfd")) { "Unsupported benchmark handler: $handlerMode" }
    require(handlerMode == "inline" || workers == 1) { "Benchmark handler requires one worker" }
    check(reactor_signals(REACTOR_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serveEpoll(
            port = port,
            host = host,
            workers = workers,
            stopRequested = { reactor_signals(REACTOR_READ_STOP) != 0 },
            onListening = { println("NativeServe benchmark_handler=$handlerMode") },
            wakeHandlerCompletions = handlerMode == "eventfd",
        ) {
            parityRoutes(suspendJson = handlerMode != "inline")
        }
    } finally {
        reactor_signals(REACTOR_RESTORE_SIGNALS)
    }
}
