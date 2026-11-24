@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.christian_draeger.ktserve

import io.github.christian_draeger.ktserve.transport.openPlatformTransport
import io.github.christian_draeger.ktserve.transport.openUringTransport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val routes = Routes().apply(configure)
    val backend = getenv("KTSERVE_BACKEND")?.toKString() ?: "io_uring"
    val transport = when (backend) {
        "io_uring" -> openUringTransport(host, port, limits.connections)
        "epoll" -> openPlatformTransport(host, port, limits.connections).also {
            println("ktServe backend=epoll tcp=ipv4")
        }
        else -> error("Unsupported ktServe backend: $backend")
    }
    try {
        HttpServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}
