package dev.perflab.nativeserve.example

import dev.perflab.nativeserve.Routes
import dev.perflab.nativeserve.badRequest
import dev.perflab.nativeserve.json
import dev.perflab.nativeserve.text
import kotlinx.serialization.Serializable

@Serializable
private data class User(val id: Int, val name: String, val active: Boolean)

private fun sampleJson() = json(User(42, "benchmark-user", true))

internal fun Routes.parityRoutes(suspendJson: Boolean = false) {
    get("/health") { text("ok") }
    if (suspendJson) getSuspending("/json") { sampleJson() }
    else get("/json") { sampleJson() }
    get("/users/{id}") { request ->
        val id = request.pathInt("id") ?: return@get badRequest()
        json(User(id, "benchmark-user", true))
    }
}
