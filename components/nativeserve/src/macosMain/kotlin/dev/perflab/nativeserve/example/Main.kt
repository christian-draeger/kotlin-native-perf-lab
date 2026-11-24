package dev.perflab.nativeserve.example

import dev.perflab.nativeserve.serve
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
    check(reactor_signals(REACTOR_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serve(port = port, host = host, stopRequested = { reactor_signals(REACTOR_READ_STOP) != 0 }, configure = { parityRoutes() })
    } finally {
        reactor_signals(REACTOR_RESTORE_SIGNALS)
    }
}
