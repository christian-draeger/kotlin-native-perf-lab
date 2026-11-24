package dev.perflab.nativeserve.example

import dev.perflab.nativeserve.Routes
import dev.perflab.nativeserve.badRequest
import dev.perflab.nativeserve.json
import dev.perflab.nativeserve.text
import kotlinx.serialization.Serializable

@Serializable
private data class User(val id: Int, val name: String, val active: Boolean)

@Serializable
private data class Catalog(val id: Int, val items: List<CatalogItem>)

@Serializable
private data class CatalogItem(val sku: String, val name: String, val description: String, val stock: Stock)

@Serializable
private data class Stock(val available: Boolean, val units: Int)

internal fun Routes.parityRoutes(catalogWorkload: Boolean = false) {
    get("/health") { text("ok") }
    get("/json") { json(User(42, "benchmark-user", true)) }
    get("/users/{id}") { request ->
        val id = request.pathInt("id") ?: return@get badRequest()
        json(User(id, "benchmark-user", true))
    }
    if (catalogWorkload) {
        get("/catalog/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            json(Catalog(id, List(32) { index ->
                CatalogItem(
                    "$id-$index", "Café item $index",
                    "Roasted in München and packed for delivery.", Stock(index > 0, index * 3),
                )
            }))
        }
    }
}
