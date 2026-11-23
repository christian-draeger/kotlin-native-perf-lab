@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package dev.perflab.nativeserve

import dev.perflab.nativeserve.transport.StreamTransport
import dev.perflab.nativeserve.transport.openSharedEpollTransports
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.MemScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking

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
        )
    }
    val transports = openSharedEpollTransports(host, port, partitions.map { it.connections })

    fun runWorker(index: Int, shouldStop: () -> Boolean) {
        try {
            HttpServer(transports[index], routes, partitions[index]).run(shouldStop)
        } catch (cause: Throwable) {
            failure.compareAndSet(null, cause)
            stopping.store(true)
        }
    }

    try {
        newFixedThreadPoolContext(workers - 1, "nativeserve-epoll").use { dispatcher ->
            println("NativeServe backend=epoll tcp=ipv4 workers=$workers")
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
        failure.load()?.let { throw it }
    } finally {
        // All workers have joined before the owner's scope releases the transports.
        transports.forEach(StreamTransport::close)
    }
}
