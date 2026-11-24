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
    val catalogWorkload = getenv("BENCHMARK_SUITE")?.toKString() == "application"
    check(reactor_signals(REACTOR_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serveEpoll(port = port, host = host, workers = workers, stopRequested = { reactor_signals(REACTOR_READ_STOP) != 0 }) {
            parityRoutes(catalogWorkload)
        }
    } finally {
        reactor_signals(REACTOR_RESTORE_SIGNALS)
    }
}
