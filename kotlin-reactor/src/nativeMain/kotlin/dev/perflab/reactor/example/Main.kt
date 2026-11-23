package dev.perflab.reactor.example

import dev.perflab.reactor.badRequest
import dev.perflab.reactor.json
import dev.perflab.reactor.serve
import dev.perflab.reactor.text
import dev.perflab.reactor.signals.REACTOR_INSTALL_SIGNALS
import dev.perflab.reactor.signals.REACTOR_READ_STOP
import dev.perflab.reactor.signals.REACTOR_RESTORE_SIGNALS
import dev.perflab.reactor.signals.reactor_signals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import platform.posix.errno
import platform.posix.getenv

@Serializable
private data class User(val id: Int, val name: String, val active: Boolean)

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    check(reactor_signals(REACTOR_INSTALL_SIGNALS) == 0) { "Install signal handlers failed (errno $errno)" }
    try {
        serve(port = port, stopRequested = { reactor_signals(REACTOR_READ_STOP) != 0 }) {
            get("/health") { text("ok") }
            get("/json") { json(User(42, "benchmark-user", true)) }
            get("/users/{id}") { request ->
                val id = request.pathInt("id") ?: return@get badRequest()
                json(User(id, "benchmark-user", true))
            }
        }
    } finally {
        reactor_signals(REACTOR_RESTORE_SIGNALS)
    }
}
