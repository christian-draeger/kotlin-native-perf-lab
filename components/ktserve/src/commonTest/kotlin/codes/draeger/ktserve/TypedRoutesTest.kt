package codes.draeger.ktserve

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class TypedRoutesTest {
    @Serializable
    private data class User(val name: String)

    @Test
    fun outputUsesTheDeclaredTypeAndAnExplicitFormatCanOverrideIt() {
        var catalog = emptyList<RegisteredRoute>()
        val routes = configuredRoutes {
            install(extension("catalog") { onStartup { catalog = routes } })
            api("/api/") {
                get("/health") { "ok" }
                get("/version") { 1 }
                get("/users") { listOf(User("Ada")) }
                get("/json", returns = json()) { "ok" }
                get("/response") { text("explicit", status = 201) }
                api("/more") { get("/health") { true } }
            }
            get("/legacy") { json(201) }
        }

        fun check(path: String, status: Int, contentType: String, body: String) {
            val response = routes.respond(Request("GET", path, null))
            assertEquals(status, response.status)
            assertEquals(contentType, response.contentType)
            assertEquals(body, response.body.decodeToString())
        }

        check("/api/health", 200, "text/plain; charset=UTF-8", "ok")
        check("/api/version", 200, "text/plain; charset=UTF-8", "1")
        check("/api/users", 200, "application/json", """[{"name":"Ada"}]""")
        check("/api/json", 200, "application/json", "\"ok\"")
        check("/api/response", 201, "text/plain; charset=UTF-8", "explicit")
        check("/api/more/health", 200, "text/plain; charset=UTF-8", "true")
        check("/legacy", 200, "application/json", "201")
        assertEquals("text/plain", catalog.single { it.template == "/api/version" }.typed?.response?.contentType)
        assertEquals("application/json", catalog.single { it.template == "/api/json" }.typed?.response?.contentType)
        assertNull(catalog.single { it.template == "/api/response" }.typed?.response)
    }

    @Test
    fun scopedValidationIsResolvedBeforeCatalogAndExplicitValidationWins() = runBlocking {
        val fragment = buildJsonObject { put("minProperties", 1) }
        val resolved = mutableListOf<String>()
        fun validation(label: String) = BodyValidation<Any?>(schema = fragment) {
            listOf(BodyViolation(label, "/name"))
        }
        fun extensionRules(label: String) = extension("validation") {
            resolveBodyValidation { type, _ ->
                assertEquals(typeOf<User>(), type)
                resolved.add(label)
                validation(label)
            }
        }
        var catalog = emptyList<RegisteredRoute>()
        val routes = configuredRoutes {
            install(extension("catalog") { onStartup { catalog = routes } })
            api("/outer") {
                post("/default") { user: User -> user }
                api("/inner") {
                    post("/local") { user: User -> user }
                    post("/override", body = jsonBody<User>(validate = validation("explicit"))) { user -> user }
                    install(extensionRules("local"))
                }
            }
            install(extensionRules("root"))
        }

        assertEquals(listOf("root", "local"), resolved)
        assertEquals(3, catalog.size)
        for (route in catalog) {
            val body = assertNotNull(route.typed?.body)
            assertEquals(typeOf<User>(), body.type)
            assertEquals(fragment, body.validation?.schema)
        }
        for ((path, label) in listOf("/outer/default" to "root", "/outer/inner/local" to "local", "/outer/inner/override" to "explicit")) {
            val response = routes.callPost(path, """{"name":"Ada"}""")
            assertEquals(422, response.status)
            assertEquals("application/problem+json", response.contentType)
            val error = Json.parseToJsonElement(response.body.decodeToString()).jsonObject.getValue("errors").jsonArray.single().jsonObject
            assertEquals(label, error.getValue("detail").jsonPrimitive.content)
            assertEquals("/name", error.getValue("pointer").jsonPrimitive.content)
        }
        assertEquals(listOf("root", "local"), resolved)
    }

    @Test
    fun typedBodyRejectsInvalidInputBeforeInvokingHandlerAndSupportsSplitUtf8() = runBlocking {
        var calls = 0
        val routes = configuredRoutes {
            api {
                post("/users", status = 201) { input: User ->
                    calls++
                    input
                }
            }
            route("POST", "/raw") { _, content -> json(content.receiveJson<User>()) }
        }
        val body = """{"name":"Élodie"}"""
        val malformed = routes.callPost("/users", """{"other":"secret"}""")
        assertEquals(400, malformed.status)
        assertEquals("application/problem+json", malformed.contentType)
        assertEquals(false, malformed.body.decodeToString().contains("secret"))
        assertEquals(400, routes.callPost("/users", chunks = listOf(byteArrayOf(0xc3.toByte()))).status)
        assertEquals(415, routes.callPost("/users", body, contentType = "text/plain").status)
        assertEquals(0, calls)

        val bytes = body.encodeToByteArray()
        val split = body.indexOf('É') + 1
        val response = routes.callPost("/users", chunks = listOf(bytes.copyOfRange(0, split), bytes.copyOfRange(split, bytes.size)))
        assertEquals(201, response.status)
        assertEquals(body, response.body.decodeToString())
        assertEquals(1, calls)
        assertEquals(body, routes.callPost("/raw", body).body.decodeToString())
    }

    @Test
    fun listenerHooksMustBeInstalledAtServerScope() {
        assertFailsWith<IllegalStateException> {
            configuredRoutes {
                api {
                    install(extension("listener") { onStartup { } })
                }
            }
        }
    }

    private suspend fun Routes.callPost(path: String, body: String, contentType: String = "application/json"): Response =
        callPost(path, listOf(body.encodeToByteArray()), contentType)

    private suspend fun Routes.callPost(
        path: String,
        chunks: List<ByteArray>,
        contentType: String = "application/json",
    ): Response = coroutineScope {
        val request = Request("POST", path, null, decodedFields = listOf(HttpField("Content-Type", contentType)))
        val content = RequestContent()
        var execute: (suspend () -> Response)? = null
        assertEquals(true, dispatchRequestRoute(request) { handler, matched, _, _ ->
            execute = { handler(matched, content) }
            true
        })
        val producer = launch {
            for (chunk in chunks) {
                while (!content.offer(chunk, chunk.size)) yield()
            }
            content.finish()
        }
        try {
            assertNotNull(execute).invoke()
        } finally {
            producer.cancelAndJoin()
        }
    }
}
