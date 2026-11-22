package dev.perflab

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

fun Application.configureApi() {
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
    }
}
