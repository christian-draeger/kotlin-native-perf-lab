package codes.draeger.ktserve.openapi

import codes.draeger.ktserve.KtServeExtensionDsl
import codes.draeger.ktserve.RegisteredRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val openApiMethods = setOf("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE")

/** Configures the document and describes or overrides individual operations. */
@KtServeExtensionDsl
class OpenApiConfiguration internal constructor() {
    private var endpoint: String? = null
    private var consumer: ((String) -> Unit)? = null
    private val operations = linkedMapOf<Pair<String, String>, OpenApiOperation>()

    /** Opt into a GET endpoint for the generated document. */
    fun serveAt(path: String) {
        check(endpoint == null) { "The OpenAPI endpoint is already configured" }
        requireOpenApiPath(path, allowParameters = false)
        endpoint = path
    }

    /** Receive the generated JSON at server startup, for example to write it to a file. */
    fun onDocument(consumer: (String) -> Unit) {
        check(this.consumer == null) { "The OpenAPI document consumer is already configured" }
        this.consumer = consumer
    }

    /** Describe or override a standalone route using its exact registered method and template. */
    fun operation(method: String, path: String, configure: OpenApiOperation.() -> Unit) {
        require(method in openApiMethods) { "Unsupported OpenAPI 3.1 method: $method" }
        requireOpenApiPath(path, allowParameters = true)
        val key = method to path
        require(key !in operations) { "OpenAPI already describes $method $path" }
        operations[key] = OpenApiOperation().apply(configure)
    }

    internal fun prepare(title: String, version: String): OpenApiDescription {
        require(title.isNotBlank()) { "The OpenAPI title must not be blank" }
        require(version.isNotBlank()) { "The OpenAPI API version must not be blank" }
        val descriptions = operations.map { (key, builder) ->
            val (method, path) = key
            builder.prepare(method, path)
        }
        return OpenApiDescription(title, version, descriptions, endpoint, consumer)
    }
}

/** The explicitly documented inputs and responses for one operation. */
@KtServeExtensionDsl
class OpenApiOperation internal constructor() {
    var summary: String? = null
    var description: String? = null

    private val parameters = linkedMapOf<Pair<String, String>, JsonObject>()
    private val responses = linkedMapOf<Int, JsonObject>()
    private var body: JsonObject? = null

    fun pathParameter(name: String, schema: JsonObject, description: String? = null) =
        parameter("path", name, schema, required = true, description = description)

    fun queryParameter(
        name: String,
        schema: JsonObject,
        required: Boolean = false,
        description: String? = null,
    ) = parameter("query", name, schema, required, description)

    fun requestBody(
        schema: JsonObject,
        contentType: String = "application/json",
        required: Boolean = false,
        description: String? = null,
    ) {
        check(body == null) { "The OpenAPI request body is already configured" }
        body = buildJsonObject {
            description?.let { put("description", it) }
            put("required", required)
            put("content", schemaContent(contentType, schema))
        }
    }

    /** Document a response. An omitted schema describes a response without content. */
    fun response(
        status: Int,
        description: String,
        schema: JsonObject? = null,
        contentType: String = "application/json",
    ) {
        require(status in 100..599) { "Expected an HTTP response status from 100 to 599" }
        require(description.isNotBlank()) { "Response $status needs a description" }
        require(status !in responses) { "OpenAPI already describes response $status" }
        responses[status] = buildJsonObject {
            put("description", description)
            if (schema != null) put("content", schemaContent(contentType, schema))
        }
    }

    private fun parameter(location: String, name: String, schema: JsonObject, required: Boolean, description: String?) {
        require(name.isNotBlank()) { "An OpenAPI parameter name must not be blank" }
        val key = location to name
        require(key !in parameters) { "OpenAPI already describes $location parameter '$name'" }
        parameters[key] = buildJsonObject {
            put("name", name)
            put("in", location)
            description?.let { put("description", it) }
            put("required", required)
            put("schema", schema.snapshot())
        }
    }

    internal fun prepare(method: String, path: String): OperationDescription {
        val operation = buildJsonObject {
            summary?.let { put("summary", it) }
            description?.let { put("description", it) }
            if (parameters.isNotEmpty()) put("parameters", JsonArray(parameters.values.toList()))
            body?.let { put("requestBody", it) }
            put("responses", buildJsonObject {
                responses.forEach { (status, response) -> put(status.toString(), response) }
            })
        }
        val pathParameters = parameters.keys.mapNotNull { (location, name) -> name.takeIf { location == "path" } }.toSet()
        return OperationDescription(method, path, pathParameters, operation)
    }
}

internal data class OperationDescription(
    val method: String,
    val path: String,
    val pathParameters: Set<String>,
    val document: JsonObject,
)

internal class OpenApiDescription(
    private val title: String,
    private val version: String,
    private val operations: List<OperationDescription>,
    val endpoint: String?,
    val consumer: ((String) -> Unit)?,
) {
    fun render(routes: List<RegisteredRoute>): String {
        val registered = routes.associateBy { it.method to it.template }
        val schemas = DescriptorSchemas()
        val documented = operations.associateByTo(linkedMapOf()) { it.method to it.path }
        for (key in documented.keys) {
            require(key in registered) { "OpenAPI route is not registered: ${key.first} ${key.second}" }
        }
        for ((key, route) in registered) {
            if (route.method !in openApiMethods) continue
            val metadata = route.typed ?: continue
            val explicit = documented[key]
            if (metadata.response == null && explicit == null) continue
            requireOpenApiPath(route.template, allowParameters = true)
            documented[key] = describeTypedRoute(route, explicit, schemas)
        }

        val templates = mutableMapOf<String, String>()
        documented.values.forEach { operation ->
            val (method, path, pathParameters) = operation
            val route = registered.getValue(method to path)
            require(route.pathParameters.toSet() == pathParameters) {
                "OpenAPI operation $method $path must describe exactly these path parameters: ${route.pathParameters.joinToString()}"
            }
            require((operation.document.getValue("responses") as JsonObject).isNotEmpty()) {
                "OpenAPI operation $method $path needs at least one response"
            }
            val shape = path.split('/').joinToString("/") { if (it.startsWith('{')) "{}" else it }
            val existing = templates.getOrPut(shape) { path }
            require(existing == path) { "OpenAPI paths $existing and $path use the same template with different parameter names" }
        }
        return buildJsonObject {
            put("openapi", "3.1.2")
            put("info", buildJsonObject {
                put("title", title)
                put("version", version)
            })
            put("paths", buildJsonObject {
                documented.values.groupBy { it.path }.forEach { (path, items) ->
                    put(path, buildJsonObject {
                        items.forEach { put(it.method.lowercase(), it.document) }
                    })
                }
            })
            val definitions = schemas.definitions()
            if (definitions.isNotEmpty()) put("components", buildJsonObject { put("schemas", definitions) })
        }.toString()
    }
}

internal fun schemaContent(contentType: String, schema: JsonObject): JsonObject {
    val parts = contentType.split('/')
    require(parts.size == 2 && parts.all { it.isNotEmpty() && it.all(Char::isHttpTokenCharacter) }) {
        "Expected a media type without parameters, such as application/json"
    }
    return buildJsonObject {
        put(contentType, buildJsonObject { put("schema", schema.snapshot()) })
    }
}

private fun Char.isHttpTokenCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "!#\$%&'*+-.^_`|~"

private fun requireOpenApiPath(path: String, allowParameters: Boolean) {
    require(path.startsWith('/') && path.split('/').all { segment ->
        if (allowParameters && segment.startsWith('{') && segment.endsWith('}')) {
            val name = segment.substring(1, segment.lastIndex)
            name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }
        } else {
            segment.isUriSegment()
        }
    }) { "Expected an absolute OpenAPI path with URI-encoded literal segments: $path" }
}

private fun String.isUriSegment(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index++]
        if (char == '%') {
            if (index + 1 >= length || !this[index].isHexDigit() || !this[index + 1].isHexDigit()) return false
            index += 2
        } else if (char !in 'a'..'z' && char !in 'A'..'Z' && char !in '0'..'9' && char !in "-._~!\$&'()*+,;=:@") {
            return false
        }
    }
    return true
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun JsonElement.snapshot(): JsonElement = when (this) {
    is JsonObject -> buildJsonObject { this@snapshot.forEach { (key, value) -> put(key, value.snapshot()) } }
    is JsonArray -> buildJsonArray { this@snapshot.forEach { add(it.snapshot()) } }
    is JsonPrimitive -> this
}
