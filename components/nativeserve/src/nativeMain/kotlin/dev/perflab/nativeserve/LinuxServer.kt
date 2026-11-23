@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve

import dev.perflab.nativeserve.transport.openEpollTransport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = serveEpoll(port, host, limits, 1, stopRequested, configure)

internal fun serveEpoll(
    port: Int,
    host: String,
    limits: ServerLimits = ServerLimits(),
    workers: Int,
    stopRequested: () -> Boolean,
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    require(workers in 1..limits.connections) { "Worker count must be between 1 and the connection limit" }
    val backend = getenv("NATIVESERVE_BACKEND")?.toKString() ?: "epoll"
    require(backend == "epoll") { "Unsupported NativeServe backend: $backend" }
    val routes = Routes().apply(configure)
    if (workers > 1) {
        runEpollWorkers(host, port, workers, limits, routes, stopRequested)
        return@memScoped
    }
    val transport = openEpollTransport(host, port, limits.connections)
    try {
        println("NativeServe backend=$backend tcp=ipv4")
        HttpServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}
