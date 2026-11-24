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
    val catalogWorkload = getenv("BENCHMARK_SUITE")?.toKString() == "application"
    embeddedServer(CIO, host = host, port = port) {
        configureApi(catalogWorkload)
    }.start(wait = true)
}
