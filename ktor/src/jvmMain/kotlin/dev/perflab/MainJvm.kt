package dev.perflab

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    embeddedServer(CIO, host = host, port = port) {
        configureApi()
    }.start(wait = true)
}
