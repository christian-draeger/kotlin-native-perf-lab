package io.github.christian_draeger.ktserve.example

import io.github.christian_draeger.ktserve.serve
import io.github.christian_draeger.ktserve.signals.KTSERVE_INSTALL_SIGNALS
import io.github.christian_draeger.ktserve.signals.KTSERVE_READ_STOP
import io.github.christian_draeger.ktserve.signals.KTSERVE_RESTORE_SIGNALS
import io.github.christian_draeger.ktserve.signals.ktserve_signals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
    check(ktserve_signals(KTSERVE_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serve(port = port, host = host, stopRequested = { ktserve_signals(KTSERVE_READ_STOP) != 0 }, configure = { parityRoutes() })
    } finally {
        ktserve_signals(KTSERVE_RESTORE_SIGNALS)
    }
}
