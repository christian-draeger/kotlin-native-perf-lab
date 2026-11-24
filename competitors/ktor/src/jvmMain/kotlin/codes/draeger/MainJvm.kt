package codes.draeger

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    val catalogWorkload = System.getenv("BENCHMARK_SUITE") == "application"
    embeddedServer(CIO, host = host, port = port) {
        configureApi(catalogWorkload)
    }.start(wait = true)
}
