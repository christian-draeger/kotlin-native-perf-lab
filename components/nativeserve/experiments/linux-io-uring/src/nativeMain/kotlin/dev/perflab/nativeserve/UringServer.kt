@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve

import dev.perflab.nativeserve.transport.openPlatformTransport
import dev.perflab.nativeserve.transport.openUringTransport
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
    val backend = getenv("NATIVESERVE_BACKEND")?.toKString() ?: "io_uring"
    val transport = when (backend) {
        "io_uring" -> openUringTransport(host, port, limits.connections)
        "epoll" -> openPlatformTransport(host, port, limits.connections).also {
            println("NativeServe backend=epoll tcp=ipv4")
        }
        else -> error("Unsupported NativeServe backend: $backend")
    }
    try {
        HttpServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}
