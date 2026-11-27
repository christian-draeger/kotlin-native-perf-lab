package codes.draeger.ktserve.validation

import codes.draeger.ktserve.jsonBody
import codes.draeger.ktserve.serve
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JvmValidationTest {
    @Test
    fun routeUsesTheInheritedAnnotationUnlessExplicitRulesOverrideIt() {
        val port = ServerSocket(0).use { it.localPort }
        val stop = AtomicBoolean(false)
        val listening = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val server = thread(name = "ktserve-validation-test-loop", isDaemon = true) {
            try {
                serve(port = port, stopRequested = stop::get, onListening = { listening.complete(Unit) }) {
                    api("/scoped") {
                        install(validation())
                        api("/nested") {
                            post("/users", status = 201) { input: User -> input }
                        }
                        val alternateRules = rules<User> { minLength(User::name, 1) }
                        post("/imports", status = 201, body = jsonBody(validate = alternateRules.asBodyValidation())) { input -> input }
                    }
                    api { post("/unvalidated", status = 201) { input: User -> input } }
                }
                finished.complete(Unit)
            } catch (failure: Throwable) {
                listening.completeExceptionally(failure)
                finished.completeExceptionally(failure)
            }
        }

        try {
            listening.get(5, TimeUnit.SECONDS)
            val invalid = post(port, "/scoped/nested/users", "A")
            assertEquals(422, invalid.statusCode())
            assertEquals("application/problem+json", invalid.headers().firstValue("Content-Type").orElse(null))
            val errors = Json.parseToJsonElement(invalid.body()).jsonObject.getValue("errors").jsonArray
            assertEquals("/name", errors.single().jsonObject.getValue("pointer").jsonPrimitive.content)
            assertEquals(201, post(port, "/scoped/nested/users", "Ada").statusCode())
            assertEquals(201, post(port, "/scoped/imports", "A").statusCode())
            assertEquals(422, post(port, "/scoped/imports", "").statusCode())
            assertEquals(201, post(port, "/unvalidated", "").statusCode())
        } finally {
            stop.set(true)
            finished.get(5, TimeUnit.SECONDS)
            server.join()
        }
    }

    private fun post(port: Int, path: String, name: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(User(name))))
            .timeout(Duration.ofSeconds(5))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Serializable
    @ValidateWith(User.Rules::class)
    data class User(val name: String) {
        object Rules : KonformRules<User> by rules({ minLength(User::name, 2) })
    }

    private companion object {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    }
}
