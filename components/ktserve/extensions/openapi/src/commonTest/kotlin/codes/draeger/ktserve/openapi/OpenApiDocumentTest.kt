package codes.draeger.ktserve.openapi

import codes.draeger.ktserve.BodyValidationMetadata
import codes.draeger.ktserve.RegisteredRoute
import codes.draeger.ktserve.TypedBodyMetadata
import codes.draeger.ktserve.TypedResponseMetadata
import codes.draeger.ktserve.TypedRouteMetadata
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.reflect.typeOf

class OpenApiDocumentTest {
    @Test
    fun documentsOnlyDescribedOperationsWithTheirExplicitParametersAndSchemas() {
        val integer = schema("integer")
        val user = schema("object")
        val problem = schema("string")
        val description = OpenApiConfiguration().apply {
            operation("GET", "/users/{id}") {
                summary = "Find a user"
                pathParameter("id", integer)
                queryParameter("details", schema("boolean"))
                response(200, "User", user)
                response(404, "User not found", problem, contentType = "text/plain")
            }
            operation("PUT", "/users/{id}") {
                pathParameter("id", integer)
                requestBody(user, required = true)
                response(204, "Saved")
            }
            operation("GET", "/release%20notes") {
                response(200, "Release notes", schema("string"), contentType = "text/plain")
            }
        }.prepare("User API", "2026-11")

        assertNull(description.endpoint)
        val document = Json.parseToJsonElement(description.render(listOf(
            RegisteredRoute("GET", "/internal", emptyList()),
            RegisteredRoute("PUT", "/users/{id}", listOf("id")),
            RegisteredRoute("GET", "/users/{id}", listOf("id")),
            RegisteredRoute("GET", "/release%20notes", emptyList()),
        ))).jsonObject
        assertEquals("3.1.2", document["openapi"]?.jsonPrimitive?.content)
        assertEquals("2026-11", document.obj("info")["version"]?.jsonPrimitive?.content)
        assertEquals(listOf("/users/{id}", "/release%20notes"), document.obj("paths").keys.toList())
        val users = document.obj("paths").obj("/users/{id}")
        assertEquals(listOf("get", "put"), users.keys.toList())
        val get = users.obj("get")
        assertEquals("Find a user", get["summary"]?.jsonPrimitive?.content)
        val (id, details) = get.getValue("parameters").jsonArray.map { it.jsonObject }
        assertEquals("id", id["name"]?.jsonPrimitive?.content)
        assertEquals("path", id["in"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), id["required"])
        assertEquals(integer, id["schema"])
        assertEquals("query", details["in"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(false), details["required"])
        assertEquals(user, get.obj("responses").obj("200").obj("content").obj("application/json")["schema"])
        assertEquals(problem, get.obj("responses").obj("404").obj("content").obj("text/plain")["schema"])

        val body = users.obj("put").obj("requestBody")
        assertEquals(JsonPrimitive(true), body["required"])
        assertEquals(user, body.obj("content").obj("application/json")["schema"])
        assertNull(users.obj("put").obj("responses").obj("204")["content"])
    }

    @Test
    fun rejectsUnknownRoutesAndMistypedPathParameters() {
        val router = listOf(RegisteredRoute("GET", "/items/{id}", listOf("id")))
        val documented = document {
            operation("GET", "/items/{id}") {
                pathParameter("key", schema("integer"))
                response(200, "Item")
            }
        }
        val wrongParameter = assertFailsWith<IllegalArgumentException> { documented.render(router) }
        assertTrue(wrongParameter.message.orEmpty().contains("exactly these path parameters: id"))
        val missingRoute = assertFailsWith<IllegalArgumentException> { documented.render(emptyList()) }
        assertTrue(missingRoute.message.orEmpty().contains("GET /items/{id}"))
    }

    @Test
    fun rejectsEquivalentTemplatesWithDifferentNamesAcrossMethods() {
        val documented = document {
            operation("GET", "/items/{id}") {
                pathParameter("id", schema("integer"))
                response(200, "Item")
            }
            operation("PATCH", "/items/{key}") {
                pathParameter("key", schema("integer"))
                response(204, "Saved")
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            documented.render(listOf(
                RegisteredRoute("GET", "/items/{id}", listOf("id")),
                RegisteredRoute("PATCH", "/items/{key}", listOf("key")),
            ))
        }
        assertTrue(failure.message.orEmpty().contains("same template"))
    }

    @Test
    fun rejectsIncompleteOrAmbiguousMetadata() {
        assertFailsWith<IllegalArgumentException> {
            document { operation("GET", "/items") {} }.render(listOf(RegisteredRoute("GET", "/items", emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            document { operation("CUSTOM", "/items") { response(200, "Items") } }
        }
        assertFailsWith<IllegalArgumentException> {
            document { operation("GET", "/bad%2") { response(200, "Item") } }
        }
        assertFailsWith<IllegalArgumentException> {
            document { serveAt("/openapi/{name}") }
        }
        assertFailsWith<IllegalArgumentException> {
            document {
                operation("GET", "/items") { response(200, "Items") }
                operation("GET", "/items") { response(200, "Item list") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            document {
                operation("GET", "/items") {
                    response(200, "Items", schema("object"))
                    response(200, "Item list", schema("array"))
                }
            }
        }
    }

    @Test
    fun derivesJsonSchemasFromWireNamesAndKeepsConstraintsLocalToTheRequest() {
        val nameRule = buildJsonObject {
            put("properties", buildJsonObject {
                put("display_name", buildJsonObject { put("minLength", 2) })
            })
        }
        val body = TypedBodyMetadata(
            typeOf<CreateUser>(), CreateUser.serializer().descriptor, "application/json", BodyValidationMetadata(nameRule),
        )
        val output = TypedResponseMetadata(201, "application/json", CreateUser.serializer().descriptor)
        val result = rendered(RegisteredRoute("POST", "/users", emptyList(), TypedRouteMetadata(body, output)))
        val post = result.obj("paths").obj("/users").obj("post")
        val bodySchema = post.obj("requestBody").obj("content").obj("application/json").obj("schema")
        val (shape, constraints) = bodySchema.getValue("allOf").jsonArray
        assertEquals(nameRule, constraints)
        val input = result.definition(shape.jsonObject)
        val outputSchema = post.obj("responses").obj("201").obj("content").obj("application/json").obj("schema")
        val response = result.definition(outputSchema)
        assertEquals(JsonArray(listOf(JsonPrimitive("display_name"), JsonPrimitive("role"))), input["required"])
        val properties = input.obj("properties")
        assertEquals(schema("string"), properties["display_name"])
        val role = properties.obj("role").getValue("anyOf").jsonArray
        assertEquals(schema("null"), role.last())
        assertEquals(JsonArray(listOf(JsonPrimitive("member"), JsonPrimitive("admin"))), role.first().jsonObject["enum"])
        assertEquals(schema("string"), properties.obj("tags")["items"])
        assertEquals(schema("string"), properties.obj("settings")["additionalProperties"])
        assertNull(input["additionalProperties"])
        assertEquals(JsonPrimitive(false), response["additionalProperties"])

        val responses = post.obj("responses")
        assertEquals(setOf("201", "400", "413", "415", "422"), responses.keys)
        assertNull(responses.obj("413")["content"])
        val problem = responses.obj("422").obj("content").obj("application/problem+json").obj("schema")
        assertEquals(schema("string"), problem.obj("properties").obj("errors").obj("items").obj("properties")["pointer"])
    }

    @Test
    fun documentsTheEffectiveWireFormatAndRecursion() {
        val tree = RegisteredRoute(
            "GET", "/trees/{id}", listOf("id"),
            TypedRouteMetadata(null, TypedResponseMetadata(200, "application/json", Tree.serializer().descriptor)),
        )
        val health = RegisteredRoute(
            "GET", "/health", emptyList(),
            TypedRouteMetadata(null, TypedResponseMetadata(200, "text/plain; charset=UTF-8", Int.serializer().descriptor)),
        )
        val result = rendered(tree, health)
        val paths = result.obj("paths")
        val treeOperation = paths.obj("/trees/{id}").obj("get")
        val type = treeOperation.obj("responses").obj("200").obj("content").obj("application/json").obj("schema")
        val children = result.definition(type).obj("properties").obj("children")
        assertEquals(type, children["items"])
        val parent = result.definition(type).obj("properties").obj("parent").getValue("anyOf").jsonArray
        assertEquals(type, parent.first())
        assertEquals(schema("null"), parent.last())
        val (id) = treeOperation.getValue("parameters").jsonArray
        assertEquals(schema("string"), id.jsonObject["schema"])
        val status = paths.obj("/health").obj("get").obj("responses").obj("200").obj("content")
        assertEquals(schema("string"), status.obj("text/plain")["schema"])
    }

    @Test
    fun layersExplicitMetadataOverTypedRoutesAndLeavesRawRoutesOptIn() {
        val typed = TypedRouteMetadata(null, TypedResponseMetadata(200, "application/json", Tree.serializer().descriptor))
        val description = document {
            operation("GET", "/summary") { summary = "Metadata only" }
            operation("GET", "/trees/{id}/{child}") {
                summary = "A branch"
                pathParameter("id", schema("integer"))
                response(404, "No such branch")
            }
            operation("GET", "/tree") {
                response(200, "Custom schema", schema("string"))
            }
        }
        val result = Json.parseToJsonElement(description.render(listOf(
            RegisteredRoute("GET", "/summary", emptyList(), typed),
            RegisteredRoute("GET", "/trees/{id}/{child}", listOf("id", "child"), typed),
            RegisteredRoute("GET", "/tree", emptyList(), typed),
            RegisteredRoute("GET", "/raw", emptyList(), TypedRouteMetadata(null, null)),
        ))).jsonObject
        val paths = result.obj("paths")
        val branch = paths.obj("/trees/{id}/{child}").obj("get")
        assertEquals(JsonPrimitive("A branch"), branch["summary"])
        assertEquals(setOf("200", "404"), branch.obj("responses").keys)
        assertEquals(listOf(schema("integer"), schema("string")), branch.getValue("parameters").jsonArray.map {
            it.jsonObject["schema"]
        })
        val response = paths.obj("/tree").obj("get").obj("responses").obj("200")
        assertEquals(schema("string"), response.obj("content").obj("application/json")["schema"])
        val summary = paths.obj("/summary").obj("get")
        assertEquals(JsonPrimitive("Metadata only"), summary["summary"])
        assertEquals(setOf("200"), summary.obj("responses").keys)
        assertNull(paths["/raw"])
    }

    @Test
    fun resolvesRecursiveGenericComponentsWithoutMergingDifferentTypeArguments() {
        val schemas = DescriptorSchemas()
        val text = schemas.schema(Link.serializer(String.serializer()).descriptor, input = false)
        val number = schemas.schema(Link.serializer(Int.serializer()).descriptor, input = false)
        val result = buildJsonObject {
            put("components", buildJsonObject { put("schemas", schemas.definitions()) })
        }
        val textProperties = result.definition(text).obj("properties")
        val numberProperties = result.definition(number).obj("properties")
        assertEquals(schema("string"), textProperties["value"])
        assertEquals(typeSchema("integer", "int32"), numberProperties["value"])
        assertEquals(text, textProperties.obj("next").getValue("anyOf").jsonArray.first())
        assertEquals(number, numberProperties.obj("next").getValue("anyOf").jsonArray.first())
    }

    @Test
    fun capturesCallerSchemasAtConfigurationTime() {
        val fields = mutableMapOf<String, JsonElement>("type" to JsonPrimitive("string"))
        val description = document {
            operation("GET", "/item") { response(200, "Item", JsonObject(fields)) }
        }
        fields["type"] = JsonPrimitive("integer")
        val generated = Json.parseToJsonElement(description.render(listOf(
            RegisteredRoute("GET", "/item", emptyList()),
        ))).jsonObject
        val response = generated.obj("paths").obj("/item").obj("get").obj("responses").obj("200")
        assertEquals(schema("string"), response.obj("content").obj("application/json")["schema"])
    }

    private fun document(configure: OpenApiConfiguration.() -> Unit) =
        OpenApiConfiguration().apply(configure).prepare("Example", "1.0.0")

    private fun rendered(vararg routes: RegisteredRoute): JsonObject =
        Json.parseToJsonElement(document {}.render(routes.toList())).jsonObject

    private fun JsonObject.definition(ref: JsonObject): JsonObject =
        obj("components").obj("schemas").obj(ref.getValue("\$ref").jsonPrimitive.content.substringAfterLast('/'))

    private fun schema(type: String) = buildJsonObject { put("type", type) }

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    private data class CreateUser(
        @SerialName("display_name") val displayName: String,
        val role: Role?,
        val tags: List<String> = emptyList(),
        val settings: Map<String, String> = emptyMap(),
    )

    @Serializable
    private enum class Role {
        @SerialName("member") Member,
        @SerialName("admin") Admin,
    }

    @Serializable
    private data class Tree(val parent: Tree? = null, val children: List<Tree> = emptyList())

    @Serializable
    private data class Link<T>(val value: T, val next: Link<T>? = null)
}
