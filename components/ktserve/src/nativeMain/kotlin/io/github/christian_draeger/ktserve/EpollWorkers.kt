@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package io.github.christian_draeger.ktserve

import io.github.christian_draeger.ktserve.transport.StreamTransport
import io.github.christian_draeger.ktserve.transport.openWorkerEpollTransports
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.toKString
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import platform.posix.getenv

internal fun MemScope.runEpollWorkers(
    host: String,
    port: Int,
    workers: Int,
    limits: ServerLimits,
    routes: Routes,
    stopRequested: () -> Boolean,
) {
    val stopping = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>(null)
    val partitions = List(workers) { index ->
        val connections = limits.connections / workers + if (index < limits.connections % workers) 1 else 0
        ServerLimits(
            connections = connections,
            headerBytes = limits.headerBytes,
            responseBodyBytes = limits.responseBodyBytes,
            idleMillis = limits.idleMillis,
            requestHeadMillis = limits.requestHeadMillis,
            drainMillis = limits.drainMillis,
            requestBodyBytes = limits.requestBodyBytes,
            requestBodyMillis = limits.requestBodyMillis,
            handlerMillis = limits.handlerMillis,
            responseHeaderBytes = limits.responseHeaderBytes,
        )
    }
    val listener = getenv("KTSERVE_WORKER_LISTENER")?.toKString() ?: "shared"
    require(listener == "shared" || listener == "reuseport") { "Unsupported worker listener: $listener" }
    val transports = openWorkerEpollTransports(host, port, partitions.map { it.connections }, listener == "reuseport")
    val counters = if (getenv("KTSERVE_WORKER_DIAGNOSTICS")?.toKString() == "1") {
        List(workers) { HttpServerCounters() }
    } else null

    fun runWorker(index: Int, shouldStop: () -> Boolean) {
        try {
            HttpServer(transports[index], routes, partitions[index], counters?.get(index)).run(shouldStop)
        } catch (cause: Throwable) {
            failure.compareAndSet(null, cause)
            stopping.store(true)
        }
    }

    try {
        newFixedThreadPoolContext(workers - 1, "ktserve-epoll").use { dispatcher ->
            println("ktServe backend=epoll tcp=ipv4 workers=$workers")
            println("ktServe worker_listener=$listener")
            runBlocking {
                try {
                    for (index in 1 until workers) {
                        launch(dispatcher) { runWorker(index) { stopping.load() } }
                    }
                    runWorker(0) {
                        if (stopRequested()) stopping.store(true)
                        stopping.load()
                    }
                } finally {
                    stopping.store(true)
                }
            }
        }
        counters?.forEachIndexed { index, values ->
            println("ktServe worker=$index accepted=${values.accepted} requests=${values.requests}")
        }
        failure.load()?.let { throw it }
    } finally {
        // All workers have joined before the owner's scope releases the transports.
        transports.forEach(StreamTransport::close)
    }
}
