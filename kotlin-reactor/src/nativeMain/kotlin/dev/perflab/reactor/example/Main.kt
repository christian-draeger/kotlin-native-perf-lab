package dev.perflab.reactor.example

import dev.perflab.reactor.badRequest
import dev.perflab.reactor.json
import dev.perflab.reactor.serve
import dev.perflab.reactor.text
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import platform.posix.getenv

@Serializable
private data class User(val id: Int, val name: String, val active: Boolean)

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    serve(port = port) {
        get("/health") { text("ok") }
        get("/json") { json(User(42, "benchmark-user", true)) }
        get("/users/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            json(User(id, "benchmark-user", true))
        }
    }
}
