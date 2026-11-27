package codes.draeger.ktserve.openapi

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.json
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.staticFiles
import codes.draeger.ktserve.text
import codes.draeger.ktserve.validation.KonformRules
import codes.draeger.ktserve.validation.ValidateWith
import codes.draeger.ktserve.validation.rules
import codes.draeger.ktserve.validation.validation
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JvmOpenApiTest {
    @Test
    fun typedRouteAndOpenApiUseTheResolvedValidationAndResponseFormat() {
        val rule = buildJsonObject {
            put("properties", buildJsonObject { put("name", buildJsonObject { put("minLength", 2) }) })
        }
        withServer({
            install(openApi("Users", "1.0") { serveAt("/openapi.json") })
            api("/v1") {
                post("/users", status = 201) { input: CreateUser -> User(input.name) }
                get("/health") { "ok" }
                get("/health-json", returns = json()) { "ok" }
                install(validation())
            }
        }) { port ->
            val doc = Json.parseToJsonElement(request(port, "/openapi.json").body()).jsonObject
            val paths = doc.getValue("paths").jsonObject
            val post = paths.getValue("/v1/users").jsonObject.getValue("post").jsonObject
            val body = post.getValue("requestBody").jsonObject.getValue("content").jsonObject
            val schemas = body.getValue("application/json").jsonObject.getValue("schema").jsonObject
            assertEquals(rule, schemas.getValue("allOf").jsonArray.last())
            val responses = post.getValue("responses").jsonObject
            assertEquals(setOf("201", "400", "413", "415", "422"), responses.keys)

            fun postBody(body: String): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/users"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build()
                return client.send(request, HttpResponse.BodyHandlers.ofString())
            }
            val created = postBody("""{"name":"Ada"}""")
            assertEquals(201, created.statusCode())
            assertEquals("Ada", Json.parseToJsonElement(created.body()).jsonObject.getValue("name").jsonPrimitive.content)
            val rejected = postBody("""{"name":"A"}""")
            assertEquals(422, rejected.statusCode())
            assertEquals("application/problem+json", rejected.headers().firstValue("Content-Type").orElseThrow())
            assertEquals("ok", request(port, "/v1/health").body())
            assertEquals("\"ok\"", request(port, "/v1/health-json").body())
        }
    }

    @Test
    fun servesOneDocumentForExplicitlyDocumentedRegisteredRoutes() {
        val documents = ConcurrentLinkedQueue<String>()
        val fixture = Files.createTempDirectory("ktserve-openapi-static")
        val asset = fixture.resolve("asset.txt")
        try {
            Files.writeString(asset, "static asset")
            val canMountStaticFiles = Files.newDirectoryStream(fixture).use { it is SecureDirectoryStream<*> }
            withServer({
                install(openApi(title = "Users", version = "1.0") {
                    serveAt("/api.json")
                    onDocument { documents.add(it) }
                    operation("GET", "/users/{id}") {
                        pathParameter("id", schema = stringSchema)
                        response(200, "User found")
                        response(404, "User missing")
                    }
                    operation("PATCH", "/users/{id}") {
                        pathParameter("id", schema = stringSchema)
                        response(200, "User updated")
                    }
                })
                get("/users/{id}") { request -> text("user ${request.pathParameter("id")}") }
                route("PATCH", "/users/{id}") { request, _ -> text("updated ${request.pathParameter("id")}") }
                get("/internal") { text("internal") }
                if (canMountStaticFiles) staticFiles(fixture.toString(), at = "/public")
            }) { port ->
                val first = request(port, "/api.json")
                assertEquals(200, first.statusCode())
                assertTrue(first.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
                val second = request(port, "/api.json")
                assertEquals(200, second.statusCode())
                assertEquals(first.body(), second.body())
                assertEquals(listOf(first.body()), documents.toList())

                val document = Json.parseToJsonElement(first.body()).jsonObject
                assertEquals(JsonPrimitive("3.1.2"), document["openapi"])
                val paths = document.getValue("paths").jsonObject
                assertEquals(setOf("/users/{id}"), paths.keys)
                val user = paths.getValue("/users/{id}").jsonObject
                assertEquals(setOf("get", "patch"), user.keys)
                val read = user.getValue("get").jsonObject
                val parameter = read.getValue("parameters").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("id"), parameter["name"])
                assertEquals(JsonPrimitive(true), parameter["required"])
                val responses = read.getValue("responses").jsonObject
                assertEquals(setOf("200", "404"), responses.keys)
                assertEquals(JsonPrimitive("User missing"), responses.getValue("404").jsonObject["description"])

                assertEquals("user 7", request(port, "/users/7").body())
                assertEquals("updated 7", request(port, "/users/7", method = "PATCH").body())
                assertEquals("internal", request(port, "/internal").body())
                if (canMountStaticFiles) assertEquals("static asset", request(port, "/public/asset.txt").body())
            }
        } finally {
            Files.deleteIfExists(asset)
            Files.deleteIfExists(fixture)
        }
    }

    @Test
    fun installationCanExportWithoutServingAnEndpoint() {
        val documents = ConcurrentLinkedQueue<String>()
        withServer({
            get("/health") { text("ok") }
            install(openApi(title = "Health", version = "1.0") {
                onDocument { documents.add(it) }
                operation("GET", "/health") { response(200, "Healthy") }
            })
        }) { port ->
            assertEquals(404, request(port, "/openapi.json").statusCode())
            val document = Json.parseToJsonElement(documents.single()).jsonObject
            assertEquals(setOf("/health"), document.getValue("paths").jsonObject.keys)
        }
    }

    @Test
    fun failsBeforeListeningWhenTheDocumentedRouteIsMissing() {
        assertRejectedBeforeListen("/absent") {
            install(openApi(title = "Example", version = "1.0") {
                operation("GET", "/absent") { response(200, "Success") }
            })
        }
    }

    @Test
    fun failsBeforeListeningWhenTheEndpointCollidesWithAnApplicationRoute() {
        assertRejectedBeforeListen("/openapi.json") {
            install(openApi(title = "Example", version = "1.0") {
                serveAt("/openapi.json")
                operation("GET", "/status") { response(200, "Success") }
            })
            get("/status") { text("ok") }
            route("GET", "/openapi.json") { _, _ -> text("existing") }
        }
    }

    private fun assertRejectedBeforeListen(path: String, configure: Routes.() -> Unit) {
        var listened = false
        val failure = assertFails {
            serve(port = freePort(), stopRequested = { true }, onListening = { listened = true }, configure = configure)
        }
        assertFalse(listened)
        assertTrue(failure.message.orEmpty().contains(path), failure.message)
    }

    private fun withServer(configure: Routes.() -> Unit, assertResponses: (Int) -> Unit) {
        val port = freePort()
        val stop = AtomicBoolean(false)
        val listening = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val server = thread(name = "ktserve-openapi-test-loop", isDaemon = true) {
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun request(port: Int, path: String, method: String = "GET"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(5))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        val stringSchema = buildJsonObject { put("type", "string") }
        val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    }

    @Serializable
    @ValidateWith(CreateUser.Rules::class)
    data class CreateUser(val name: String) {
        object Rules : KonformRules<CreateUser> by rules({
            minLength(CreateUser::name, 2)
        })
    }

    @Serializable
    private data class User(val name: String)
}
