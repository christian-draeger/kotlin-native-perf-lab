@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.AdaptiveEpollTransport
import codes.draeger.ktserve.transport.withAdaptiveEpollListener
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.CLOCK_THREAD_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.fflush
import platform.posix.timespec

private const val SAMPLE_MILLIS = 1_000
private const val STANDBY_SAMPLE_MILLIS = 100
private const val BUSY_PERCENT = 75
private const val QUIET_PERCENT = 30
private const val QUIET_MILLIS = 5_000

internal fun MemScope.runAdaptiveEpollWorkers(
    host: String,
    port: Int,
    limits: ServerLimits,
    routes: Routes,
    stopRequested: () -> Boolean,
    onListening: () -> Unit,
    handoffEnabled: Boolean,
    standby: Boolean,
) = withAdaptiveEpollListener(host, port, limits.connections, requireWakeup = handoffEnabled || standby) { openWorker, stopAccepts ->
    val primary = openWorker()
    val stopping = AtomicBoolean(false)
    val startedAt = TimeSource.Monotonic.markNow()
    fun report(event: String) {
        println("ktServe adaptive $event elapsed_ms=${startedAt.elapsedNow().inWholeMilliseconds}")
        fflush(null)
    }

    try {
        runBlocking {
            val sampleMillis = if (standby) STANDBY_SAMPLE_MILLIS else SAMPLE_MILLIS
            val busyWindows = if (standby) 2 else 3
            val quietWindows = QUIET_MILLIS / sampleMillis
            val load = EpollWorkerLoad(sampleMillis)
            fun startWorker() = SecondaryEpollWorker(
                this, openWorker, routes, limits, stopping, startedAt, standby, sampleMillis,
                createHandoff = {
                    if (handoffEnabled) EpollKeepAliveHandoff(startedAt, limits, ::report) else null
                },
                report = ::report,
            )
            val cleanupDispatcher = newSingleThreadContext("ktserve-worker-cleanup")
            val cleanupQueue = Channel<SecondaryEpollWorker>(1)
            val cleaning = AtomicBoolean(false)
            val cleanupReady = CompletableDeferred<Unit>()
            val cleaner = launch(cleanupDispatcher, start = CoroutineStart.ATOMIC) {
                withContext(NonCancellable) {
                    cleanupReady.complete(Unit)
                    for (worker in cleanupQueue) {
                        val started = TimeSource.Monotonic.markNow()
                        worker.close()
                        report("worker_cleanup_ms=${started.elapsedNow().inWholeMilliseconds}")
                        if (worker.started.load()) report("active_workers=1")
                        cleaning.store(false)
                    }
                }
            }
            var secondary: SecondaryEpollWorker? = null
            var hot = 0
            var quiet = 0
            var scaleUpFailed = false
            try {
                cleanupReady.await()
                println("ktServe backend=epoll tcp=ipv4 workers=adaptive")
                if (handoffEnabled) println("ktServe adaptive handoff=enabled")
                report("active_workers=1")
                if (standby) {
                    secondary = startWorker()
                    secondary.awaitStandby()
                }
                onListening()
                val server = HttpServer(primary, routes, limits, clock = startedAt)
                server.run shouldStop@ {
                    if (stopRequested() || !isActive) {
                        stopping.store(true)
                        stopAccepts()
                        secondary?.wake()
                        return@shouldStop true
                    }
                    secondary?.split(primary, server)
                    load.sample()?.let { percent ->
                        val worker = secondary
                        if (worker != null && worker.job.isCompleted && worker.recover(primary, server)) {
                            cleaning.store(true)
                            check(cleanupQueue.trySend(worker).isSuccess)
                            secondary = null
                            hot = 0
                            quiet = 0
                            scaleUpFailed = worker.failed.load()
                        }
                        hot = if (percent >= BUSY_PERCENT) minOf(hot + 1, busyWindows) else 0
                        val current = secondary
                        val busy = hot == busyWindows && (!standby || server.hasMultipleActiveConnections(500))
                        if (current == null) {
                            if (busy && !scaleUpFailed && !cleaning.load()) {
                                try {
                                    secondary = startWorker()
                                } catch (cause: Exception) {
                                    scaleUpFailed = true
                                    report("worker_failed=2 error=${cause.message}")
                                }
                            }
                        } else {
                            val bothQuiet = percent < QUIET_PERCENT && current.load.load() in 0 until QUIET_PERCENT
                            quiet = if (bothQuiet) minOf(quiet + 1, quietWindows) else 0
                            if (busy) current.setWanted(true)
                            if (quiet == quietWindows) current.setWanted(false)
                        }
                    }
                    false
                }
            } finally {
                stopping.store(true)
                stopAccepts()
                withContext(NonCancellable) {
                    try {
                        secondary?.let { worker ->
                            worker.wake()
                            worker.job.join()
                            worker.close()
                        }
                    } finally {
                        cleanupQueue.close()
                        cleaner.join()
                        cleanupDispatcher.close()
                    }
                }
            }
        }
    } finally {
        primary.close()
    }
}

private sealed interface WorkerDemand {
    data object Parked : WorkerDemand
    data class Serving(val handoff: EpollKeepAliveHandoff?, val retiring: Boolean = false) : WorkerDemand
}

private class SecondaryEpollWorker(
    scope: CoroutineScope,
    openWorker: MemScope.() -> AdaptiveEpollTransport,
    routes: Routes,
    limits: ServerLimits,
    stopping: AtomicBoolean,
    clock: TimeMark,
    private val standby: Boolean,
    sampleMillis: Int,
    private val createHandoff: () -> EpollKeepAliveHandoff?,
    report: (String) -> Unit,
) {
    private val dispatcher = newFixedThreadPoolContext(1, "ktserve-adaptive-epoll")
    private val demand = AtomicReference<WorkerDemand>(
        if (standby) WorkerDemand.Parked else WorkerDemand.Serving(createHandoff()),
    )
    private val wakeLoop = AtomicReference<(() -> Unit)?>(null)
    private val standbyReady = CompletableDeferred<Boolean>()
    val started = AtomicBoolean(false)
    val failed = AtomicBoolean(false)
    val load = AtomicInt(-1)
    val job = scope.launch(dispatcher) {
        try {
            memScoped {
                val transport = openWorker()
                try {
                    val usage = EpollWorkerLoad(sampleMillis)
                    var active = !standby
                    var retiring = false
                    val server = HttpServer(transport, routes, limits, clock = clock)
                    wakeLoop.store(transport.signalApplicationProgress)
                    if (standby) {
                        transport.pauseAccepts(true)
                        load.store(0)
                        report("standby_ready=2")
                        standbyReady.complete(true)
                    } else {
                        started.store(true)
                        report("active_workers=2")
                        (demand.load() as? WorkerDemand.Serving)?.handoff?.targetReady(transport)
                    }
                    server.run shouldStop@ {
                        if (stopping.load()) return@shouldStop true
                        val state = demand.load() as? WorkerDemand.Serving ?: return@shouldStop false
                        if (!active) {
                            active = true
                            retiring = state.retiring
                            usage.reset()
                            transport.pauseAccepts(retiring)
                            started.store(true)
                            report("active_workers=2")
                            state.handoff?.targetReady(transport)
                            if (retiring) report("retiring_worker=2")
                        } else if (retiring != state.retiring) {
                            retiring = state.retiring
                            transport.pauseAccepts(retiring)
                            report(if (retiring) "retiring_worker=2" else "resumed_worker=2")
                        }
                        val receivedPending = state.handoff?.receive(transport, server) ?: true
                        usage.sample()?.let(load::store)
                        if (retiring && receivedPending && !transport.hasConnections &&
                            demand.compareAndSet(state, WorkerDemand.Parked)
                        ) {
                            if (!standby) return@shouldStop true
                            active = false
                            load.store(0)
                            report("parked_worker=2")
                            report("active_workers=1")
                        }
                        false
                    }
                } finally {
                    transport.close()
                }
            }
        } catch (cause: Exception) {
            failed.store(true)
            report("worker_failed=2 error=${cause.message}")
        } finally {
            standbyReady.complete(false)
        }
    }

    suspend fun awaitStandby() {
        check(standbyReady.await()) { "Could not prepare the standby worker" }
    }

    fun setWanted(wanted: Boolean) {
        while (true) {
            val current = demand.load()
            val next = when (current) {
                WorkerDemand.Parked -> {
                    if (!wanted || !standby) return
                    WorkerDemand.Serving(createHandoff())
                }
                is WorkerDemand.Serving -> {
                    if (current.retiring == !wanted) return
                    current.copy(retiring = !wanted)
                }
            }
            if (demand.compareAndSet(current, next)) {
                wake()
                return
            }
        }
    }

    fun split(transport: AdaptiveEpollTransport, server: HttpServer) {
        val state = demand.load() as? WorkerDemand.Serving ?: return
        val handoff = state.handoff ?: return
        if (!handoff.sent && !state.retiring && !job.isCompleted) handoff.split(transport, server)
    }

    fun recover(transport: AdaptiveEpollTransport, server: HttpServer): Boolean =
        (demand.load() as? WorkerDemand.Serving)?.handoff?.receive(transport, server, "handoff_restored") ?: true

    fun wake() {
        wakeLoop.load()?.invoke()
    }

    fun close() {
        (demand.load() as? WorkerDemand.Serving)?.handoff?.close()
        dispatcher.close()
    }
}

private class EpollWorkerLoad(private val sampleMillis: Int) {
    private val clock = TimeSource.Monotonic.markNow()
    private var lastWall = 0L
    private var lastCpu = threadCpuMillis()

    fun reset() {
        lastWall = clock.elapsedNow().inWholeMilliseconds
        lastCpu = threadCpuMillis()
    }

    fun sample(): Int? {
        val wall = clock.elapsedNow().inWholeMilliseconds
        if (wall - lastWall < sampleMillis) return null
        val cpu = threadCpuMillis()
        val percent = 100 * (cpu - lastCpu) / (wall - lastWall)
        lastWall = wall
        lastCpu = cpu
        return percent.coerceIn(0, 100).toInt()
    }
}

private fun threadCpuMillis(): Long = memScoped {
    val time = alloc<timespec>()
    check(clock_gettime(CLOCK_THREAD_CPUTIME_ID, time.ptr) == 0) { "Could not sample epoll worker CPU time" }
    time.tv_sec * 1_000 + time.tv_nsec / 1_000_000
}
