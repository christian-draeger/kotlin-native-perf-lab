package codes.draeger.ktserve.openapi

import codes.draeger.ktserve.RegisteredRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun describeTypedRoute(
    route: RegisteredRoute,
    explicit: OperationDescription?,
    schemas: DescriptorSchemas,
): OperationDescription {
    val typed = checkNotNull(route.typed)
    val pathParameters = route.pathParameters.toSet()
    val explicitParameters = explicit?.pathParameters.orEmpty()
    require(pathParameters.containsAll(explicitParameters)) {
        "OpenAPI operation ${route.method} ${route.template} can only describe these path parameters: ${route.pathParameters.joinToString()}"
    }
    val responseOverrides = explicit?.document?.get("responses")?.jsonObject.orEmpty()
    require(typed.response != null || responseOverrides.isNotEmpty()) {
        "OpenAPI operation ${route.method} ${route.template} needs at least one response"
    }
    val operation = buildJsonObject {
        explicit?.document?.forEach { (name, value) -> put(name, value) }
        val parameters = (explicit?.document?.get("parameters") as? JsonArray).orEmpty() +
            route.pathParameters.filterNot { it in explicitParameters }.map(::implicitPathParameter)
        if (parameters.isNotEmpty()) put("parameters", JsonArray(parameters))

        val body = typed.body
        if (body != null && explicit?.document?.containsKey("requestBody") != true) {
            val shape = schemas.schema(body.descriptor, input = true)
            val constraints = body.validation?.schema
            val schema = if (constraints == null || constraints.isEmpty()) shape else buildJsonObject {
                put("allOf", buildJsonArray {
                    add(shape)
                    add(constraints)
                })
            }
            put("requestBody", buildJsonObject {
                put("required", true)
                put("content", schemaContent(body.contentType, schema))
            })
        }

        val success = typed.response
        val errors = if (body == null) emptyMap() else buildMap {
            put(400, "Invalid JSON or request body")
            put(413, "Request body exceeds the server limit")
            put(415, "Unsupported request body media type")
            if (body.validation != null) put(422, "Request body failed validation")
        }
        put("responses", buildJsonObject {
            responseOverrides.forEach { (status, response) -> put(status, response) }
            val statuses = listOfNotNull(success?.status) + errors.keys
            for (status in statuses.distinct()) {
                if (status.toString() in responseOverrides) continue
                val response = success?.takeIf { it.status == status }
                val issue = errors[status]
                put(status.toString(), buildJsonObject {
                    put("description", if (response != null) "Response" else checkNotNull(issue))
                    val content = buildJsonObject {
                        response?.let {
                            val mediaType = it.contentType.substringBefore(';')
                            val schema = if (mediaType == "text/plain") typeSchema("string")
                            else schemas.schema(it.descriptor, input = false)
                            schemaContent(mediaType, schema).forEach { (name, value) -> put(name, value) }
                        }
                        if (issue != null && status != 413) {
                            val problem = schemaContent("application/problem+json", problemSchema(status == 422))
                            problem.forEach { (name, value) -> put(name, value) }
                        }
                    }
                    if (content.isNotEmpty()) put("content", content)
                })
            }
        })
    }
    return OperationDescription(route.method, route.template, pathParameters, operation)
}

private fun implicitPathParameter(name: String) = buildJsonObject {
    put("name", name)
    put("in", "path")
    put("required", true)
    put("schema", typeSchema("string"))
}

private fun problemSchema(validationErrors: Boolean) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("type", typeSchema("string", "uri-reference"))
        put("title", typeSchema("string"))
        put("status", typeSchema("integer"))
        put("detail", typeSchema("string"))
        if (validationErrors) put("errors", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { add("detail") })
                put("properties", buildJsonObject {
                    put("detail", typeSchema("string"))
                    put("pointer", typeSchema("string"))
                })
            })
        })
    })
    put("required", buildJsonArray {
        listOf("type", "title", "status", "detail").forEach { add(it) }
        if (validationErrors) add("errors")
    })
}
