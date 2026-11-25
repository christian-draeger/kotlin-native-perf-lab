package codes.draeger.examples.hello

import codes.draeger.ktserve.ServerEvent
import codes.draeger.ktserve.ServerObserver
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal class ConsoleObserver : ServerObserver, AutoCloseable {
    val ready = AtomicBoolean()
    private val running = AtomicBoolean(true)
    private val droppedEvents = AtomicLong()
    private val events = ArrayBlockingQueue<ServerEvent>(1024)

    private val logger = thread(name = "ktserve-observer", isDaemon = true) {
        var active = 0
        var rejected = 0L
        var exchanges = 0L
        var timeouts = 0L
        var reportAt = System.nanoTime()
        while (running.get() || events.isNotEmpty()) {
            when (val event = events.poll(200, TimeUnit.MILLISECONDS)) {
                is ServerEvent.Connections -> active = event.active
                ServerEvent.AdmissionRejected -> rejected++
                is ServerEvent.Lifecycle -> println("event=lifecycle state=${event.name}")
                is ServerEvent.Exchange -> {
                    exchanges++
                    if (event.outcome.name.endsWith("_TIMEOUT")) timeouts++
                    println(
                        "event=exchange connection=${event.connection} request=${event.request} " +
                            "status=${event.status} duration_ns=${event.durationNanos} outcome=${event.outcome}",
                    )
                }
                null -> Unit
            }
            val now = System.nanoTime()
            if (now >= reportAt) {
                println(
                    "event=metrics active_connections=$active rejected_connections=$rejected " +
                        "exchanges=$exchanges timeouts=$timeouts dropped_events=${droppedEvents.get()}",
                )
                reportAt = now + TimeUnit.SECONDS.toNanos(5)
            }
        }
    }

    override fun onEvent(event: ServerEvent) {
        if (event is ServerEvent.Lifecycle) ready.set(event == ServerEvent.Lifecycle.READY)
        if (!events.offer(event)) droppedEvents.incrementAndGet()
    }

    override fun close() {
        running.set(false)
        logger.join(500)
    }
}
