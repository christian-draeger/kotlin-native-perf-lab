package codes.draeger.ktserve.cors

import codes.draeger.ktserve.HttpField
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmCorsTest {
    @Test
    fun preflightFinishesBeforeAuthWhileOrdinaryOptionsReachesTheApp() = withServer({
        install(cors {
            allowOrigins(appOrigin)
            allowMethods("GET", "PATCH")
            allowHeaders("Content-Type", "X-Client")
            allowCredentials = true
        })
        beforeEach { request ->
            if (request.header("Authorization") == "test") null else text("auth required", status = 401)
        }
        route("OPTIONS", "/items") { _, _ -> text("ordinary OPTIONS") }
    }) { port ->
        val granted = request(
            port, "OPTIONS", "/items",
            "Origin" to appOrigin,
            "Access-Control-Request-Method" to "PATCH",
            "Access-Control-Request-Headers" to "Content-Type, X-Client",
        )
        assertEquals(204, granted.statusCode())
        assertEquals(appOrigin, granted.header("Access-Control-Allow-Origin"))
        assertEquals("true", granted.header("Access-Control-Allow-Credentials"))
        assertEquals("GET, PATCH", granted.header("Access-Control-Allow-Methods"))
        assertEquals("content-type, x-client", granted.header("Access-Control-Allow-Headers"))
        assertEquals("Origin, Access-Control-Request-Method, Access-Control-Request-Headers", granted.header("Vary"))

        val rejected = request(
            port, "OPTIONS", "/items",
            "Origin" to appOrigin,
            "Access-Control-Request-Method" to "PATCH",
            "Access-Control-Request-Headers" to "Authorization",
        )
        assertEquals(403, rejected.statusCode())
        assertNull(rejected.header("Access-Control-Allow-Origin"))
        assertEquals(granted.header("Vary"), rejected.header("Vary"))

        val ordinary = request(port, "OPTIONS", "/items", "Origin" to appOrigin, "Authorization" to "test")
        assertEquals(200, ordinary.statusCode())
        assertEquals("ordinary OPTIONS", ordinary.body())
        assertNull(ordinary.header("Access-Control-Allow-Methods"))
        assertEquals(granted.header("Vary"), ordinary.header("Vary"))
    }

    @Test
    fun normalAndErrorResponsesRespectPolicyAndMergeExistingVary() = withServer({
        install(cors { allowOrigins(appOrigin) })
        beforeEach { request ->
            if (request.path == "/restricted") text("unauthorized", status = 401).withHeaders(
                HttpField("Vary", "Accept-Encoding, ORIGIN"),
                HttpField("vary", "Accept-Language"),
                HttpField("Access-Control-Allow-Origin", "*"),
                HttpField("Access-Control-Allow-Credentials", "true"),
            ) else null
        }
        get("/error") { error("expected handler failure") }
        get("/wild") { text("ok").withHeaders(HttpField("Vary", "*")) }
    }) { port ->
        val allowed = request(port, "GET", "/restricted", "Origin" to appOrigin)
        assertEquals(401, allowed.statusCode())
        assertEquals(listOf(appOrigin), allowed.headers().allValues("Access-Control-Allow-Origin"))
        assertNull(allowed.header("Access-Control-Allow-Credentials"))
        assertEquals(listOf("Accept-Encoding, ORIGIN, Accept-Language"), allowed.headers().allValues("Vary"))

        val denied = request(port, "GET", "/restricted", "Origin" to "https://other.example")
        assertNull(denied.header("Access-Control-Allow-Origin"))
        assertEquals(allowed.header("Vary"), denied.header("Vary"))
        val absent = request(port, "GET", "/restricted")
        assertNull(absent.header("Access-Control-Allow-Origin"))
        assertEquals(allowed.header("Vary"), absent.header("Vary"))

        val handlerFailure = request(port, "GET", "/error", "Origin" to appOrigin)
        assertEquals(500, handlerFailure.statusCode())
        assertEquals(appOrigin, handlerFailure.header("Access-Control-Allow-Origin"))
        val wildcardVary = request(port, "GET", "/wild", "Origin" to appOrigin)
        assertEquals("*", wildcardVary.header("Vary"))
        assertEquals(appOrigin, wildcardVary.header("Access-Control-Allow-Origin"))
    }

    private fun withServer(configure: Routes.() -> Unit, assertResponses: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val stop = AtomicBoolean(false)
        val listening = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val server = thread(isDaemon = true) {
            try {
                serve(port = port, stopRequested = stop::get, onListening = { listening.complete(Unit) }, configure = configure)
                finished.complete(Unit)
            } catch (failure: Throwable) {
                listening.completeExceptionally(failure)
                finished.completeExceptionally(failure)
            }
        }
        try {
            listening.get(5, TimeUnit.SECONDS)
            assertResponses(port)
        } finally {
            stop.set(true)
            finished.get(5, TimeUnit.SECONDS)
            server.join()
        }
    }

    private fun request(port: Int, method: String, path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(5))
        for ((name, value) in headers) request.header(name, value)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

    private companion object {
        const val appOrigin = "https://app.example"
        val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    }
}
