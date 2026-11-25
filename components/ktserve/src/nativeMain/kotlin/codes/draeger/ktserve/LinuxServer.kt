@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.openPlatformTransport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** How ktServe uses the effective Linux CPU budget when the caller selects a policy. */
enum class LinuxWorkerPolicy {
    /** Start two shared-listener event loops when the budget permits, otherwise one. */
    EAGER,

    /** Start a parked second loop and activate it under load when the budget permits. */
    STANDBY,
}

internal enum class EpollWorkerMode { EAGER, STANDBY, COLD }

internal data class EpollWorkerConfig(
    val workers: Int = 1,
    val mode: EpollWorkerMode? = null,
    val handoff: Boolean = false,
)

internal fun configuredEpollWorkers(defaultMode: EpollWorkerMode? = EpollWorkerMode.EAGER): EpollWorkerConfig {
    val fixed = getenv("KTSERVE_WORKERS")?.toKString()?.toInt()
    val mode = when (val value = getenv("KTSERVE_ADAPTIVE_WORKERS")?.toKString()) {
        null -> if (fixed == null) defaultMode else null
        "0" -> null
        "1" -> EpollWorkerMode.COLD
        "eager" -> EpollWorkerMode.EAGER
        "standby" -> EpollWorkerMode.STANDBY
        else -> error("Unsupported automatic worker policy: $value")
    }
    return EpollWorkerConfig(fixed ?: 1, mode, getenv("KTSERVE_ADAPTIVE_HANDOFF")?.toKString() == "1")
}

/** Serve with the configured Linux policy, which defaults to budgeted eager workers. */
fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = serveEpoll(port, host, limits, configuredEpollWorkers(), stopRequested, configure = configure)

/** Serve HTTP and notify the caller after the listener has successfully bound. */
fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    onListening: () -> Unit,
    configure: Routes.() -> Unit,
): Unit = serveEpoll(port, host, limits, configuredEpollWorkers(), stopRequested, onListening, configure)

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
): Unit = serveEpoll(port, host, limits, EpollWorkerConfig(workers), stopRequested, onListening, configure)

/**
 * Serve with an explicit Linux worker policy. Handlers and observers can run concurrently.
 * Standby can hand off existing connections when route exchange observation is disabled.
 */
fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    workerPolicy: LinuxWorkerPolicy,
    stopRequested: () -> Boolean = { false },
    onListening: () -> Unit = {},
    configure: Routes.() -> Unit,
) {
    val mode = when (workerPolicy) {
        LinuxWorkerPolicy.EAGER -> EpollWorkerMode.EAGER
        LinuxWorkerPolicy.STANDBY -> EpollWorkerMode.STANDBY
    }
    serveEpoll(port, host, limits, EpollWorkerConfig(mode = mode), stopRequested, onListening, configure)
}

internal fun serveEpoll(
    port: Int,
    host: String,
    limits: ServerLimits = ServerLimits(),
    workerConfig: EpollWorkerConfig,
    stopRequested: () -> Boolean,
    onListening: () -> Unit = {},
    configure: Routes.() -> Unit,
): Unit = memScoped {
    val (workers, mode, handoff) = workerConfig
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    require(workers in 1..limits.connections) { "Worker count must be between 1 and the connection limit" }
    require(mode == null || workers == 1) { "Automatic worker policies select their own worker count" }
    require(!handoff || mode == EpollWorkerMode.COLD || mode == EpollWorkerMode.STANDBY) {
        "Keep-alive handoff requires demand-based workers"
    }
    val backend = getenv("KTSERVE_BACKEND")?.toKString() ?: "epoll"
    require(backend == "epoll") { "Unsupported ktServe backend: $backend" }
    val routes = configuredRoutes(configure)
    if (mode == EpollWorkerMode.COLD) {
        require(getenv("KTSERVE_WORKER_LISTENER")?.toKString().let { it == null || it == "shared" }) {
            "Adaptive workers require the shared listener"
        }
        require(!handoff || routes.observer == null) { "The handoff pilot requires unobserved connections" }
    }
    if (mode != null) {
        val canStartSecond = limits.connections >= 2 && canRunTwoEpollWorkers()
        if (mode != EpollWorkerMode.COLD) println("ktServe adaptive worker_policy=${mode.name.lowercase()}")
        if (mode == EpollWorkerMode.EAGER) println("ktServe worker_policy=eager workers=${if (canStartSecond) 2 else 1}")
        if (canStartSecond) {
            if (mode == EpollWorkerMode.EAGER) {
                runEpollWorkers(host, port, 2, limits, routes, stopRequested, onListening, eager = true)
            } else {
                runAdaptiveEpollWorkers(
                    host, port, limits, routes, stopRequested, onListening,
                    handoffEnabled = (handoff || mode == EpollWorkerMode.STANDBY) && routes.observer == null,
                    standby = mode == EpollWorkerMode.STANDBY,
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
