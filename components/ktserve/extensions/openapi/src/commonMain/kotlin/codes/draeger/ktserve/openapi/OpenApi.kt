package codes.draeger.ktserve.openapi

import codes.draeger.ktserve.ServerExtension
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.response

/** Generate an OpenAPI 3.1 document from typed and explicitly described standalone routes at startup. */
fun openApi(
    title: String,
    version: String,
    configure: OpenApiConfiguration.() -> Unit = {},
): ServerExtension {
    val description = OpenApiConfiguration().apply(configure).prepare(title, version)
    return extension("codes.draeger.ktserve.openapi") {
        onStartup {
            val document = description.render(routes)
            description.endpoint?.let { path ->
                val snapshot = response(document.encodeToByteArray(), contentType = "application/json")
                get(path) { snapshot }
            }
            description.consumer?.invoke(document)
        }
    }
}
