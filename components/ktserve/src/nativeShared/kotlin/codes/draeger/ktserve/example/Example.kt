package codes.draeger.ktserve.example

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.badRequest
import codes.draeger.ktserve.json
import codes.draeger.ktserve.text
import kotlinx.serialization.Serializable

@Serializable
internal data class User(val id: Int, val name: String, val active: Boolean)

@Serializable
internal data class Catalog(val id: Int, val items: List<CatalogItem>)

@Serializable
internal data class CatalogItem(val sku: String, val name: String, val description: String, val stock: Stock)

@Serializable
internal data class Stock(val available: Boolean, val units: Int)

internal fun benchmarkUser(id: Int) = User(id, "benchmark-user", true)

internal fun benchmarkCatalog(id: Int) = Catalog(id, List(32) { index ->
    CatalogItem(
        "$id-$index", "Café item $index",
        "Roasted in München and packed for delivery.", Stock(index > 0, index * 3),
    )
})

internal fun Routes.parityRoutes(suite: String? = null) {
    get("/health") { text("ok") }
    get("/json") { json(benchmarkUser(42)) }
    get("/users/{id}") { request ->
        val id = request.pathInt("id") ?: return@get badRequest()
        json(benchmarkUser(id))
    }
    if (suite == "application" || suite == "application-mix") {
        get("/catalog/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            json(benchmarkCatalog(id))
        }
    }
    if (suite == "application-mix") applicationMixRoutes()
}
