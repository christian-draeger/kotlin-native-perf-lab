package io.github.christian_draeger.ktserve.example

import io.github.christian_draeger.ktserve.Routes
import io.github.christian_draeger.ktserve.badRequest
import io.github.christian_draeger.ktserve.json
import io.github.christian_draeger.ktserve.text
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
