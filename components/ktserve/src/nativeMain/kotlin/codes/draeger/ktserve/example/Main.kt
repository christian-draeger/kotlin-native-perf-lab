package codes.draeger.ktserve.example

import codes.draeger.ktserve.ServerEvent
import codes.draeger.ktserve.serveEpoll
import codes.draeger.ktserve.signals.KTSERVE_INSTALL_SIGNALS
import codes.draeger.ktserve.signals.KTSERVE_READ_STOP
import codes.draeger.ktserve.signals.KTSERVE_RESTORE_SIGNALS
import codes.draeger.ktserve.signals.ktserve_signals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
    val workers = getenv("KTSERVE_WORKERS")?.toKString()?.toInt() ?: 1
    val adaptiveWorkers = getenv("KTSERVE_ADAPTIVE_WORKERS")?.toKString() == "1"
    val suite = getenv("BENCHMARK_SUITE")?.toKString()
    val observeExchanges = getenv("KTSERVE_BENCHMARK_OBSERVE")?.toKString() == "1"
    require(!observeExchanges || workers == 1 && !adaptiveWorkers) { "The observation benchmark uses one fixed worker" }
    var exchanges = 0L
    var listening = false
    check(ktserve_signals(KTSERVE_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serveEpoll(
            port = port,
            host = host,
            workers = workers,
            adaptiveWorkers = adaptiveWorkers,
            stopRequested = { ktserve_signals(KTSERVE_READ_STOP) != 0 },
            onListening = {
                check(!listening) { "The listener notified readiness twice" }
                listening = true
            },
            configure = {
                parityRoutes(suite)
                if (observeExchanges) observe { if (it is ServerEvent.Exchange) exchanges++ }
            },
        )
        check(listening) { "The listener never notified readiness" }
    } finally {
        ktserve_signals(KTSERVE_RESTORE_SIGNALS)
        if (observeExchanges) println("ktServe observed_exchanges=$exchanges")
    }
}
