package dev.perflab

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        configureApi()
    }.start(wait = true)
}
