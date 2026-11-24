package codes.draeger

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    val suite = System.getenv("BENCHMARK_SUITE")
    val upstreamPort = if (suite == "application-upstream") requireNotNull(System.getenv("BENCHMARK_UPSTREAM_PORT")).toInt() else null
    embeddedServer(CIO, host = host, port = port) {
        configureApi(suite, upstreamPort)
    }.start(wait = true)
}
