package codes.draeger

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
    val suite = getenv("BENCHMARK_SUITE")?.toKString()
    val upstreamPort =
        if (suite == "application-upstream") requireNotNull(getenv("BENCHMARK_UPSTREAM_PORT")?.toKString()).toInt() else null
    embeddedServer(CIO, host = host, port = port) {
        configureApi(suite, upstreamPort)
    }.start(wait = true)
}
