package codes.draeger.ktserve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerExtensionTest {
    @Test
    fun requestStartObserversRunBeforeAnEarlierInstalledRequestPolicy() {
        val events = mutableListOf<String>()
        val routes = configuredRoutes {
            install(extension("example.policy") {
                onRequest {
                    events.add("policy")
                    RequestDecision.Respond(text("denied", status = 403))
                }
            })
            install(extension("example.observers") {
                onRequestStart {
                    events.add("first observer")
                    error("Observer failed")
                }
                onRequestStart { events.add("second observer") }
                onComplete { _, result -> events.add("completed ${result.status}") }
            })
        }
        val connection = HttpConnection(0, ServerLimits())
        val request = "GET /protected HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray()
        request.copyInto(connection.input.bytes)
        connection.received(request.size, now = 1)
        try {
            assertTrue(connection.prepareResponse(routes, now = 1))
            assertTrue(assertNotNull(connection.output).decodeToString().startsWith("HTTP/1.1 403 "))
            assertTrue(connection.wrote(connection.outputLength, now = 2))
        } finally {
            connection.close()
        }
        assertEquals(listOf("first observer", "second observer", "policy", "completed 403"), events)
    }

    @Test
    fun startupHooksSeeConfiguredRoutesAndCanRegisterAnEndpoint() {
        var startup: ExtensionStartup? = null
        val routes = configuredRoutes {
            install(extension("example.startup") {
                onStartup {
                    startup = this
                    get("/catalog") { text("ok") }
                }
            })
            get("/items/{id}") { text("item") }
            route("PATCH", "/items/{id}") { _, _ -> text("updated") }
            fallbackGet("/directory") { redirect("/directory/") }
        }

        val catalog = assertNotNull(startup)
        assertEquals(
            listOf(
                RegisteredRoute("PATCH", "/items/{id}", listOf("id")),
                RegisteredRoute("GET", "/items/{id}", listOf("id")),
            ),
            catalog.routes,
        )
        assertNull(routes.extensionHooks, "A startup-only extension should leave request hooks disabled")
        assertEquals("ok", routes.respond(Request("GET", "/catalog", null)).body.decodeToString())
        assertFailsWith<IllegalStateException> { catalog.get("/late") { text("too late") } }
    }

    @Test
    fun startupExtensionEndpointCannotBeShadowedByAGeneralGetRoute() {
        val failure = assertFailsWith<IllegalStateException> {
            configuredRoutes {
                route("GET", "/{path}") { _, _ -> text("catch-all") }
                install(extension("example.startup") {
                    onStartup { get("/catalog") { text("ok") } }
                })
            }
        }
        assertTrue(failure.message.orEmpty().contains("/catalog"))
    }

    @Test
    fun duplicateExtensionIdsFailAtConfiguration() {
        val failure = assertFailsWith<IllegalStateException> {
            configuredRoutes {
                install(extension("example.headers") {})
                install(extension("example.headers") {})
            }
        }
        assertTrue(failure.message.orEmpty().contains("example.headers"))
    }

    @Test
    fun responseHeaderHookCanReadMatchedRouteAndReplaceCachedStreamingHead() {
        val routes = configuredRoutes {
            install(extension("example.headers") {
                onResponseHeaders { context, headers ->
                    assertEquals(MatchedRoute("GET", "/asset/{name}"), context.route)
                    headers.set("Cache-Control", "no-store")
                    headers.remove("x-sample")
                }
            })
            get("/asset/{name}") {
                val cachedHead = "HTTP/1.1 200 OK\r\nCache-Control: public\r\n\r\n".encodeToByteArray()
                Response(
                    200,
                    "text/plain",
                    ByteArray(0),
                    ResponseStream.Pull(1, ResponseSource { _, _ -> -1 }, cachedHead),
                    fields = listOf(HttpField("Cache-Control", "public"), HttpField("X-Sample", "old")),
                )
            }
        }
        val connection = HttpConnection(0, ServerLimits())
        val request = "GET /asset/one HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray()
        request.copyInto(connection.input.bytes)
        connection.received(request.size, now = 1)
        try {
            assertTrue(connection.prepareResponse(routes, now = 1))
            val head = assertNotNull(connection.output).decodeToString()
            assertTrue(head.startsWith("HTTP/1.1 200 "))
            assertTrue(head.contains("Content-Length: 1\r\n"))
            assertEquals(listOf("Cache-Control: no-store"), head.lines().filter { it.startsWith("Cache-Control:") })
            assertTrue("X-Sample:" !in head)
        } finally {
            connection.close()
        }
    }
}
