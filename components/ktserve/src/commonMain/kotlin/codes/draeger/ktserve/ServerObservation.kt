package codes.draeger.ktserve

import kotlin.time.TimeSource

/**
 * Receives HTTP/1.x events on the owning I/O loop. Several loops can invoke the observer concurrently.
 * Return promptly and offload any logging I/O.
 */
fun interface ServerObserver {
    fun onEvent(event: ServerEvent)
}

/**
 * Receives exchange fields directly on the owning I/O loop. Return promptly and offload any I/O.
 * [onEvent] receives lifecycle and connection events. A response status of zero means no response was selected.
 */
fun interface ExchangeObserver : ServerObserver {
    /** Called once for each I/O loop. Override to provide independent state for that loop. */
    fun forLoop(): ExchangeObserver = this

    override fun onEvent(event: ServerEvent) {}

    fun onExchange(
        connection: Long,
        request: Long,
        method: String?,
        status: Int,
        durationNanos: Long,
        outcome: ExchangeOutcome,
    )
}

sealed interface ServerEvent {
    enum class Lifecycle : ServerEvent { READY, DRAINING, STOPPED }
    data class Connections(val active: Int) : ServerEvent
    data object AdmissionRejected : ServerEvent

    /**
     * Summarizes an exchange without request paths, headers, or bodies.
     * The connection and request identifiers are local to the owning server loop.
     */
    data class Exchange(
        val connection: Long,
        val request: Long,
        val method: String?,
        val status: Int?,
        val durationNanos: Long,
        val outcome: ExchangeOutcome,
    ) : ServerEvent
}

enum class ExchangeOutcome {
    COMPLETED, DISCONNECTED, IO_FAILURE, SHUTDOWN,
    IDLE_TIMEOUT, REQUEST_HEAD_TIMEOUT, REQUEST_BODY_TIMEOUT, HANDLER_TIMEOUT, RESPONSE_TIMEOUT,
}

internal fun combineObservers(application: ServerObserver?, extensions: List<ServerObserver>): ServerObserver? {
    if (extensions.isEmpty()) return application
    if (application == null && extensions.size == 1) return extensions.single()
    val observers = buildList {
        if (application != null) add(application)
        addAll(extensions)
    }
    return CompositeExchangeObserver(observers.toTypedArray())
}

private class CompositeExchangeObserver(private val observers: Array<ServerObserver>) : ExchangeObserver {
    override fun forLoop(): ExchangeObserver {
        val local = buildList {
            for (observer in observers) {
                notifyObserver { add(if (observer is ExchangeObserver) observer.forLoop() else observer) }
            }
        }
        return CompositeExchangeObserver(local.toTypedArray())
    }

    override fun onEvent(event: ServerEvent) {
        for (observer in observers) notifyObserver { observer.onEvent(event) }
    }

    override fun onExchange(
        connection: Long,
        request: Long,
        method: String?,
        status: Int,
        durationNanos: Long,
        outcome: ExchangeOutcome,
    ) {
        var event: ServerEvent.Exchange? = null
        for (observer in observers) notifyObserver {
            if (observer is ExchangeObserver) {
                observer.onExchange(connection, request, method, status, durationNanos, outcome)
            } else {
                val exchange = event ?: ServerEvent.Exchange(
                    connection, request, method, status.takeIf { it != 0 }, durationNanos, outcome,
                ).also { event = it }
                observer.onEvent(exchange)
            }
        }
    }
}

private inline fun notifyObserver(action: () -> Unit) {
    try {
        action()
    } catch (_: Exception) {
        // Observers are external callbacks. Serving and cleanup must still complete.
    }
}

internal class ServerObservation(observer: ServerObserver, timeSource: TimeSource) {
    private val loopObserver = if (observer is ExchangeObserver) observer.forLoop() else observer
    private val exchangeObserver = loopObserver as? ExchangeObserver
    private val clock = timeSource.markNow()

    fun elapsedNanos(): Long = clock.elapsedNow().inWholeNanoseconds

    fun emit(event: ServerEvent) = notifyObserver { loopObserver.onEvent(event) }

    fun emitExchange(
        connection: Long,
        request: Long,
        method: String?,
        status: Int,
        durationNanos: Long,
        outcome: ExchangeOutcome,
    ) = notifyObserver {
        if (exchangeObserver != null) {
            exchangeObserver.onExchange(connection, request, method, status, durationNanos, outcome)
        } else {
            loopObserver.onEvent(ServerEvent.Exchange(connection, request, method, status.takeIf { it != 0 }, durationNanos, outcome))
        }
    }
}

internal class ExchangeObservation(private val server: ServerObservation, private val connection: Long) {
    private var started = -1L
    private var sequence = 0L
    private var method: String? = null
    private var status = 0

    fun begin() {
        if (started < 0) {
            started = server.elapsedNanos()
            sequence++
        }
    }

    fun identify(method: String) {
        begin()
        this.method = method
    }

    fun response(status: Int) {
        this.status = status
    }

    fun end(outcome: ExchangeOutcome) {
        if (started < 0) return
        val completedMethod = method
        val completedStatus = status
        val duration = (server.elapsedNanos() - started).coerceAtLeast(0)
        started = -1L
        method = null
        status = 0
        server.emitExchange(connection, sequence, completedMethod, completedStatus, duration, outcome)
    }
}
