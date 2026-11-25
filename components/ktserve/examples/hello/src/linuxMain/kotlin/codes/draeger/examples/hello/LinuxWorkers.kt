package codes.draeger.examples.hello

import codes.draeger.ktserve.LinuxWorkerPolicy
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.usleep

fun linuxMain(args: Array<String>) {
    if (args.firstOrNull() == "--worker-check") {
        checkWorkers(args[1].toInt(), args[2], args[3].toInt())
    } else {
        main(args)
    }
}

@OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)
private fun checkWorkers(port: Int, mode: String, connections: Int) {
    val limits = ServerLimits(connections = connections, blockingHandlers = 1)
    val stopping = AtomicBoolean(false)
    val released = AtomicBoolean(false)
    val listening = AtomicInt(0)
    val onListening: () -> Unit = {
        check(listening.addAndFetch(1) == 1) { "Readiness fired twice" }
        println("Public consumer ready")
        fflush(null)
    }
    fun hold(message: String): String {
        released.store(false)
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        println(message)
        fflush(null)
        while (!released.load() && !deadline.hasPassedNow()) usleep(1_000u)
        check(released.load()) { "The other event loop did not release the held handler" }
        return "released"
    }
    val routes: Routes.() -> Unit = {
        get("/health") { text("ok") }
        get("/stop") {
            stopping.store(true)
            text("stopped")
        }
        get("/hold") { text(hold("Public consumer handler waiting")) }
        getBlocking("/blocking") { text(hold("Public consumer blocking handler waiting")) }
        get("/release") {
            released.store(true)
            text("ok")
        }
    }
    when (mode) {
        "default" -> serve(port, limits = limits, stopRequested = stopping::load, onListening = onListening, configure = routes)
        "eager", "standby" -> serve(
            port, limits = limits, workerPolicy = if (mode == "eager") LinuxWorkerPolicy.EAGER else LinuxWorkerPolicy.STANDBY,
            stopRequested = stopping::load, onListening = onListening, configure = routes,
        )
        else -> serve(
            port, limits = limits, workers = mode.toInt(), stopRequested = stopping::load,
            onListening = onListening, configure = routes,
        )
    }
    check(listening.load() == 1) { "Readiness never fired" }
}
