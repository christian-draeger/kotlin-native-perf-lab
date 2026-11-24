package codes.draeger

import codes.draeger.benchmark.upstream.UpstreamClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserResponse(val id: Int, val name: String, val active: Boolean)

@Serializable
private data class Catalog(val id: Int, val items: List<CatalogItem>)

@Serializable
private data class CatalogItem(val sku: String, val name: String, val description: String, val stock: Stock)

@Serializable
private data class Stock(val available: Boolean, val units: Int)

fun Application.configureApi(suite: String?, upstreamPort: Int? = null) {
    val upstream = upstreamPort?.let(::UpstreamClient)
    if (upstream != null) monitor.subscribe(ApplicationStopped) { upstream.close() }
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    routing {
        if (upstream != null) get("/upstream/{scenario}") {
            val result = upstream.request(requireNotNull(call.parameters["scenario"]))
            call.respondText(result.message, ContentType.Text.Plain, HttpStatusCode.fromValue(result.status))
        }
        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }
        get("/json") {
            call.respond(UserResponse(42, "benchmark-user", true))
        }
        get("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respond(UserResponse(id, "benchmark-user", true))
            }
        }
        if (suite == "application" || suite == "application-mix") {
            get("/catalog/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest)
                } else {
                    call.respond(Catalog(id, List(32) { index ->
                        CatalogItem(
                            "$id-$index", "Café item $index",
                            "Roasted in München and packed for delivery.", Stock(index > 0, index * 3),
                        )
                    }))
                }
            }
        }
        if (suite == "application-mix") applicationMixRoutes()
        if (suite == "http2-stream") {
            get("/stream") {
                call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
                    repeat(5) {
                        writeFully(ByteArray(16 * 1024) { 'x'.code.toByte() })
                        flush()
                    }
                }
            }
        }
    }
}
