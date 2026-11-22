package dev.perflab

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.toKString
import platform.posix.getenv

fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        configureApi()
    }.start(wait = true)
}
