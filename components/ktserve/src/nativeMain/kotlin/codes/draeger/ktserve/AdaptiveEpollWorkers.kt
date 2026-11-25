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
private const val BUSY_PERCENT = 75
private const val QUIET_PERCENT = 30
private const val BUSY_WINDOWS = 3
private const val QUIET_WINDOWS = 5

internal fun MemScope.runAdaptiveEpollWorkers(
    host: String,
    port: Int,
    limits: ServerLimits,
    routes: Routes,
    stopRequested: () -> Boolean,
    onListening: () -> Unit,
    handoffEnabled: Boolean,
) = withAdaptiveEpollListener(host, port, limits.connections, requireWakeup = handoffEnabled) { openWorker, stopAccepts ->
    val primary = openWorker()
    val stopping = AtomicBoolean(false)
    val startedAt = TimeSource.Monotonic.markNow()
    fun report(event: String) {
        println("ktServe adaptive $event elapsed_ms=${startedAt.elapsedNow().inWholeMilliseconds}")
        fflush(null)
    }

    try {
        runBlocking {
            val load = EpollWorkerLoad()
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
                onListening()
                val server = HttpServer(primary, routes, limits, clock = startedAt)
                server.run shouldStop@ {
                    if (stopRequested() || !isActive) {
                        stopping.store(true)
                        stopAccepts()
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
                        hot = if (percent >= BUSY_PERCENT) minOf(hot + 1, BUSY_WINDOWS) else 0
                        val current = secondary
                        if (current == null) {
                            if (hot == BUSY_WINDOWS && !scaleUpFailed && !cleaning.load()) {
                                try {
                                    secondary = SecondaryEpollWorker(
                                        this, openWorker, routes, limits, stopping, startedAt,
                                        if (handoffEnabled) EpollKeepAliveHandoff(startedAt, limits, ::report) else null,
                                        ::report,
                                    )
                                } catch (cause: Exception) {
                                    scaleUpFailed = true
                                    report("worker_failed=2 error=${cause.message}")
                                }
                            }
                        } else {
                            val bothQuiet = percent < QUIET_PERCENT && current.load.load() in 0 until QUIET_PERCENT
                            quiet = if (bothQuiet) minOf(quiet + 1, QUIET_WINDOWS) else 0
                            if (hot == BUSY_WINDOWS) current.wanted.store(true)
                            if (quiet == QUIET_WINDOWS) current.wanted.store(false)
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

private class SecondaryEpollWorker(
    scope: CoroutineScope,
    openWorker: MemScope.() -> AdaptiveEpollTransport,
    routes: Routes,
    limits: ServerLimits,
    stopping: AtomicBoolean,
    clock: TimeMark,
    private val handoff: EpollKeepAliveHandoff?,
    report: (String) -> Unit,
) {
    private val dispatcher = newFixedThreadPoolContext(1, "ktserve-adaptive-epoll")
    val wanted = AtomicBoolean(true)
    val started = AtomicBoolean(false)
    val failed = AtomicBoolean(false)
    val load = AtomicInt(-1)
    val job = scope.launch(dispatcher) {
        try {
            memScoped {
                val transport = openWorker()
                try {
                    val usage = EpollWorkerLoad()
                    var retiring = false
                    val server = HttpServer(transport, routes, limits, clock = clock)
                    started.store(true)
                    report("active_workers=2")
                    handoff?.targetReady(transport)
                    server.run shouldStop@ {
                        if (stopping.load()) return@shouldStop true
                        val receivedPending = handoff?.receive(transport, server) ?: true
                        usage.sample()?.let(load::store)
                        val shouldRetire = !wanted.load()
                        if (retiring != shouldRetire) {
                            retiring = shouldRetire
                            transport.pauseAccepts(retiring)
                            report(if (retiring) "retiring_worker=2" else "resumed_worker=2")
                        }
                        retiring && receivedPending && !transport.hasConnections
                    }
                } finally {
                    transport.close()
                }
            }
        } catch (cause: Exception) {
            failed.store(true)
            report("worker_failed=2 error=${cause.message}")
        }
    }

    fun split(transport: AdaptiveEpollTransport, server: HttpServer) {
        if (handoff != null && !handoff.sent && wanted.load() && !job.isCompleted) handoff.split(transport, server)
    }

    fun recover(transport: AdaptiveEpollTransport, server: HttpServer): Boolean =
        handoff?.receive(transport, server, "handoff_restored") ?: true

    fun close() {
        handoff?.close()
        dispatcher.close()
    }
}

private class EpollWorkerLoad {
    private val clock = TimeSource.Monotonic.markNow()
    private var lastWall = 0L
    private var lastCpu = threadCpuMillis()

    fun sample(): Int? {
        val wall = clock.elapsedNow().inWholeMilliseconds
        if (wall - lastWall < SAMPLE_MILLIS) return null
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
