package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerLimitsTest {
    @Test
    fun acceptsMinimumValues() {
        ServerLimits(
            connections = 1,
            headerBytes = 64,
            responseBodyBytes = 1,
            idleMillis = 1,
            requestHeadMillis = 1,
            drainMillis = 1,
            requestBodyBytes = 0,
            requestBodyMillis = 1,
            handlerMillis = 1,
            responseHeaderBytes = 1,
            responseMillis = 1,
            blockingHandlers = 1,
        )
    }

    @Test
    fun identifiesInvalidFieldAndItsMinimum() {
        val invalid = listOf(
            "connections must be at least 1" to { ServerLimits(connections = 0) },
            "headerBytes must be at least 64" to { ServerLimits(headerBytes = 63) },
            "responseBodyBytes must be at least 1" to { ServerLimits(responseBodyBytes = 0) },
            "idleMillis must be at least 1" to { ServerLimits(idleMillis = 0) },
            "requestHeadMillis must be at least 1" to { ServerLimits(requestHeadMillis = 0) },
            "drainMillis must be at least 1" to { ServerLimits(drainMillis = 0) },
            "requestBodyBytes must be at least 0" to { ServerLimits(requestBodyBytes = -1) },
            "requestBodyMillis must be at least 1" to { ServerLimits(requestBodyMillis = 0) },
            "handlerMillis must be at least 1" to { ServerLimits(handlerMillis = 0) },
            "responseHeaderBytes must be at least 1" to { ServerLimits(responseHeaderBytes = 0) },
            "responseMillis must be at least 1" to { ServerLimits(responseMillis = 0) },
            "blockingHandlers must be at least 1" to { ServerLimits(blockingHandlers = 0) },
        )
        for ((message, construct) in invalid) {
            val exception = assertFailsWith<IllegalArgumentException>(message) { construct() }
            assertEquals(message, exception.message)
        }
    }
}
