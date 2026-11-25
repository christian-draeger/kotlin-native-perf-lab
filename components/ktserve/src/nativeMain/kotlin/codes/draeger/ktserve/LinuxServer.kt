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
    adaptiveWorkers: Boolean = false,
    adaptiveHandoff: Boolean = false,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    require(workers in 1..limits.connections) { "Worker count must be between 1 and the connection limit" }
    require(!adaptiveWorkers || workers == 1) { "Adaptive workers start with one worker" }
    require(!adaptiveHandoff || adaptiveWorkers) { "Keep-alive handoff requires adaptive workers" }
    val backend = getenv("KTSERVE_BACKEND")?.toKString() ?: "epoll"
    require(backend == "epoll") { "Unsupported ktServe backend: $backend" }
    val routes = configuredRoutes(configure)
    require(!adaptiveHandoff || routes.observer == null) { "The handoff pilot requires unobserved connections" }
    if (adaptiveWorkers) {
        require(getenv("KTSERVE_WORKER_LISTENER")?.toKString().let { it == null || it == "shared" }) {
            "Adaptive workers require the shared listener"
        }
        if (limits.connections >= 2 && canRunTwoEpollWorkers()) {
            runAdaptiveEpollWorkers(host, port, limits, routes, stopRequested, onListening, adaptiveHandoff)
            return@memScoped
        }
        println("ktServe adaptive max_workers=1 reason=budget")
        println("ktServe adaptive active_workers=1 elapsed_ms=0")
    }
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
