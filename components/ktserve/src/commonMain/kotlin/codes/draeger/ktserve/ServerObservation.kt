package codes.draeger.ktserve

import kotlin.time.TimeSource

/**
 * Receives HTTP/1.x events on the owning I/O loop. Several loops can invoke the observer concurrently.
 * Return promptly and offload any logging I/O.
 */
fun interface ServerObserver {
    fun onEvent(event: ServerEvent)
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

internal class ServerObservation(private val observer: ServerObserver, timeSource: TimeSource) {
    private val clock = timeSource.markNow()

    fun elapsedNanos(): Long = clock.elapsedNow().inWholeNanoseconds

    fun emit(event: ServerEvent) {
        try {
            observer.onEvent(event)
        } catch (_: Exception) {
            // Observers are external callbacks. Serving and cleanup must still complete.
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
        val event = ServerEvent.Exchange(
            connection, sequence, method, status.takeIf { it != 0 },
            (server.elapsedNanos() - started).coerceAtLeast(0), outcome,
        )
        started = -1L
        method = null
        status = 0
        server.emit(event)
    }
}
