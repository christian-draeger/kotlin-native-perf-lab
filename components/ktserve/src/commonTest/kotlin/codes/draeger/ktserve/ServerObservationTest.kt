package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TestTimeSource

class ServerObservationTest {
    @Test
    fun eventObserversStillReceiveTheExistingEvents() {
        val events = mutableListOf<ServerEvent>()
        val clock = TestTimeSource()
        val server = ServerObservation(ServerObserver { events.add(it) }, clock)
        val exchange = ExchangeObservation(server, connection = 4)

        server.emit(ServerEvent.Lifecycle.READY)
        exchange.identify("GET")
        exchange.response(200)
        clock += 25.nanoseconds
        exchange.end(ExchangeOutcome.COMPLETED)
        exchange.begin()
        exchange.end(ExchangeOutcome.DISCONNECTED)

        assertEquals(
            listOf(
                ServerEvent.Lifecycle.READY,
                ServerEvent.Exchange(4, 1, "GET", 200, 25, ExchangeOutcome.COMPLETED),
                ServerEvent.Exchange(4, 2, null, null, 0, ExchangeOutcome.DISCONNECTED),
            ),
            events,
        )
    }

    @Test
    fun exchangeFieldsAndLifecycleGoToTheOwningLoopObserver() {
        val exchanges = mutableListOf<ServerEvent.Exchange>()
        val stopped = mutableListOf<Int>()
        var loops = 0
        val observer = object : ExchangeObserver {
            override fun forLoop(): ExchangeObserver {
                val loop = ++loops
                return object : ExchangeObserver {
                    override fun onEvent(event: ServerEvent) {
                        if (event == ServerEvent.Lifecycle.STOPPED) stopped.add(loop)
                    }

                    override fun onExchange(
                        connection: Long,
                        request: Long,
                        method: String?,
                        status: Int,
                        durationNanos: Long,
                        outcome: ExchangeOutcome,
                    ) {
                        exchanges.add(ServerEvent.Exchange(connection, request, method, status, durationNanos, outcome))
                        if (loop == 1) error("Observer failure must leave serving intact")
                    }
                }
            }

            override fun onExchange(
                connection: Long,
                request: Long,
                method: String?,
                status: Int,
                durationNanos: Long,
                outcome: ExchangeOutcome,
            ) = error("Exchanges belong to the loop observer")
        }
        val clock = TestTimeSource()
        val primary = ServerObservation(observer, clock)
        val secondary = ServerObservation(observer, clock)
        val first = ExchangeObservation(primary, connection = 3)
        val second = ExchangeObservation(secondary, connection = 5)

        first.identify("HEAD")
        first.response(204)
        second.begin()
        clock += 17.nanoseconds
        first.end(ExchangeOutcome.COMPLETED)
        first.end(ExchangeOutcome.DISCONNECTED)
        second.end(ExchangeOutcome.REQUEST_HEAD_TIMEOUT)
        secondary.emit(ServerEvent.Lifecycle.STOPPED)
        primary.emit(ServerEvent.Lifecycle.STOPPED)

        assertEquals(2, loops)
        assertEquals(listOf(2, 1), stopped)
        assertEquals(
            listOf(
                ServerEvent.Exchange(3, 1, "HEAD", 204, 17, ExchangeOutcome.COMPLETED),
                ServerEvent.Exchange(5, 1, null, 0, 17, ExchangeOutcome.REQUEST_HEAD_TIMEOUT),
            ),
            exchanges,
        )
    }
}
