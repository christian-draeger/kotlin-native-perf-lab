@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.openPlatformTransport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = serveEpoll(port, host, limits, 1, stopRequested, configure = configure)

/** Serve HTTP and notify the caller after the listener has successfully bound. */
fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    onListening: () -> Unit,
    configure: Routes.() -> Unit,
): Unit = serveEpoll(port, host, limits, 1, stopRequested, onListening, configure)

/**
 * Serve with an explicit number of Linux epoll loops in one process.
 * Handlers and observers shared by the loops can be invoked concurrently.
 */
fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    workers: Int,
    stopRequested: () -> Boolean = { false },
    onListening: () -> Unit = {},
    configure: Routes.() -> Unit,
): Unit = serveEpoll(port, host, limits, workers, stopRequested, onListening, configure)

internal fun serveEpoll(
    port: Int,
    host: String,
    limits: ServerLimits = ServerLimits(),
    workers: Int,
    stopRequested: () -> Boolean,
    onListening: () -> Unit = {},
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    require(workers in 1..limits.connections) { "Worker count must be between 1 and the connection limit" }
    val backend = getenv("KTSERVE_BACKEND")?.toKString() ?: "epoll"
    require(backend == "epoll") { "Unsupported ktServe backend: $backend" }
    val routes = Routes().apply(configure)
    if (workers > 1) {
        runEpollWorkers(host, port, workers, limits, routes, stopRequested, onListening)
        return@memScoped
    }
    val transport = openPlatformTransport(host, port, limits.connections)
    try {
        println("ktServe backend=$backend tcp=ipv4")
        onListening()
        HttpServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}
