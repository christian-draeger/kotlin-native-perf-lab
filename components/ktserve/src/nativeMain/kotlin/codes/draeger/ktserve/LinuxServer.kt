@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.openPlatformTransport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal enum class LinuxWorkerPolicy { COLD, EAGER, STANDBY }

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
    workerPolicy: LinuxWorkerPolicy? = null,
    adaptiveHandoff: Boolean = false,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    require(workers in 1..limits.connections) { "Worker count must be between 1 and the connection limit" }
    require(workerPolicy == null || workers == 1) { "Automatic worker policies select their own worker count" }
    require(!adaptiveHandoff || workerPolicy == LinuxWorkerPolicy.COLD || workerPolicy == LinuxWorkerPolicy.STANDBY) {
        "Keep-alive handoff requires demand-based workers"
    }
    val backend = getenv("KTSERVE_BACKEND")?.toKString() ?: "epoll"
    require(backend == "epoll") { "Unsupported ktServe backend: $backend" }
    val routes = configuredRoutes(configure)
    val handoffEnabled = adaptiveHandoff || workerPolicy == LinuxWorkerPolicy.STANDBY
    require(!handoffEnabled || routes.observer == null) { "The handoff pilot requires unobserved connections" }
    if (workerPolicy != null) {
        require(getenv("KTSERVE_WORKER_LISTENER")?.toKString().let { it == null || it == "shared" }) {
            "Adaptive workers require the shared listener"
        }
        if (workerPolicy != LinuxWorkerPolicy.COLD) println("ktServe adaptive worker_policy=${workerPolicy.name.lowercase()}")
        if (limits.connections >= 2 && canRunTwoEpollWorkers()) {
            if (workerPolicy == LinuxWorkerPolicy.EAGER) {
                runEpollWorkers(host, port, 2, limits, routes, stopRequested, onListening, awaitWorkers = true)
            } else {
                runAdaptiveEpollWorkers(
                    host, port, limits, routes, stopRequested, onListening, handoffEnabled,
                    standby = workerPolicy == LinuxWorkerPolicy.STANDBY,
                )
            }
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
