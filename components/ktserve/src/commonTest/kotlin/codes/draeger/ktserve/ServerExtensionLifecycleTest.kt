package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.TestTimeSource

class ServerExtensionLifecycleTest {
    @Test
    fun emptyAndSingleObserversKeepTheirDirectDispatch() {
        val observer = ExchangeObserver { _, _, _, _, _, _ -> }
        assertNull(configuredRoutes {}.observer)
        assertSame(observer, configuredRoutes { observe(observer) }.observer)
        assertSame(observer, configuredRoutes {
            install(extension("example.observer") { observe(observer) })
        }.observer)
    }

    @Test
    fun combinedObserversKeepIndependentLoopStateAndFailures() {
        val events = mutableListOf<ServerEvent>()
        val loops = mutableListOf<Pair<Int, Long>>()
        var nextLoop = 1
        val throwing = object : ExchangeObserver {
            override fun forLoop(): ExchangeObserver = error("Failed observer setup")

            override fun onExchange(
                connection: Long, request: Long, method: String?, status: Int, durationNanos: Long, outcome: ExchangeOutcome,
            ) = error("Failed observers must be skipped")
        }
        val exchangeObserver = object : ExchangeObserver {
            override fun forLoop(): ExchangeObserver {
                val id = nextLoop++
                return ExchangeObserver { connection, _, _, _, _, _ ->
                    loops.add(id to connection)
                    error("Failed exchange callback")
                }
            }

            override fun onExchange(
                connection: Long, request: Long, method: String?, status: Int, durationNanos: Long, outcome: ExchangeOutcome,
            ) = error("The loop owns its observations")
        }
        val routes = configuredRoutes {
            observe(ServerObserver { error("Failed application event callback") })
            install(extension("example.observers") {
                observe(throwing)
                observe(exchangeObserver)
                observe(ServerObserver { events.add(it) })
            })
        }
        val observer = checkNotNull(routes.observer)
        val first = ServerObservation(observer, TestTimeSource())
        val second = ServerObservation(observer, TestTimeSource())

        first.emit(ServerEvent.Lifecycle.READY)
        first.emitExchange(7, 1, "GET", 200, 10, ExchangeOutcome.COMPLETED)
        second.emitExchange(9, 1, null, 0, 15, ExchangeOutcome.DISCONNECTED)
        second.emit(ServerEvent.Lifecycle.STOPPED)

        assertEquals(listOf(1 to 7L, 2 to 9L), loops)
        assertEquals(
            listOf(
                ServerEvent.Lifecycle.READY,
                ServerEvent.Exchange(7, 1, "GET", 200, 10, ExchangeOutcome.COMPLETED),
                ServerEvent.Exchange(9, 1, null, null, 15, ExchangeOutcome.DISCONNECTED),
                ServerEvent.Lifecycle.STOPPED,
            ),
            events,
        )
    }

    @Test
    fun serverResourcesCloseInReverseAfterServing() {
        val order = mutableListOf<String>()
        val routes = configuredRoutes {
            install(extension("example.resources") {
                onServerStart {
                    order.add("start one")
                    AutoCloseable { order.add("close one") }
                }
                onServerStart {
                    order.add("start two")
                    AutoCloseable { order.add("close two") }
                }
            })
        }
        assertEquals(emptyList(), order)
        routes.withServerExtensions { order.add("serve") }
        assertEquals(listOf("start one", "start two", "serve", "close two", "close one"), order)
    }

    @Test
    fun startupFailureClosesAlreadyStartedResourcesAndKeepsTheCause() {
        val startupFailure = IllegalStateException("Startup failed")
        val cleanupFailure = IllegalStateException("Cleanup failed")
        val order = mutableListOf<String>()
        val routes = configuredRoutes {
            install(extension("example.rollback") {
                onServerStart {
                    AutoCloseable { order.add("close one") }
                }
                onServerStart {
                    AutoCloseable {
                        order.add("close two")
                        throw cleanupFailure
                    }
                }
                onServerStart { throw startupFailure }
            })
        }

        val thrown = assertFailsWith<IllegalStateException> {
            routes.withServerExtensions { error("Server must not start") }
        }
        assertSame(startupFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressedExceptions)
        assertEquals(listOf("close two", "close one"), order)
    }
}
