package dev.perflab.ktserve.example

import dev.perflab.ktserve.serveEpoll
import dev.perflab.ktserve.signals.KTSERVE_INSTALL_SIGNALS
import dev.perflab.ktserve.signals.KTSERVE_READ_STOP
import dev.perflab.ktserve.signals.KTSERVE_RESTORE_SIGNALS
import dev.perflab.ktserve.signals.ktserve_signals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
    val workers = getenv("KTSERVE_WORKERS")?.toKString()?.toInt() ?: 1
    val catalogWorkload = getenv("BENCHMARK_SUITE")?.toKString() == "application"
    check(ktserve_signals(KTSERVE_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serveEpoll(port = port, host = host, workers = workers, stopRequested = { ktserve_signals(KTSERVE_READ_STOP) != 0 }) {
            parityRoutes(catalogWorkload)
        }
    } finally {
        ktserve_signals(KTSERVE_RESTORE_SIGNALS)
    }
}
