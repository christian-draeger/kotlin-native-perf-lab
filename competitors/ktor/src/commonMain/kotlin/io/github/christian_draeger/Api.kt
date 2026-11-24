package io.github.christian_draeger

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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

fun Application.configureApi(catalogWorkload: Boolean) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    routing {
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
        if (catalogWorkload) {
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
    }
}
