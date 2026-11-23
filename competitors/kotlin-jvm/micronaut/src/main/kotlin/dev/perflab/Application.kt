package dev.perflab

import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.runtime.Micronaut

data class User(val id: Int, val name: String, val active: Boolean)

@Controller("/")
class Endpoints {
    @Get("/health", produces = [MediaType.TEXT_PLAIN])
    fun health() = "ok"

    @Get("/json")
    fun json() = User(42, "benchmark-user", true)

    @Get("/users/{id}")
    fun user(@PathVariable id: String): HttpResponse<User> {
        val userId = id.toIntOrNull() ?: return HttpResponse.badRequest()
        return HttpResponse.ok(User(userId, "benchmark-user", true))
    }
}

fun main() {
    System.setProperty("micronaut.server.host", System.getenv("BIND_HOST") ?: "127.0.0.1")
    System.setProperty("micronaut.server.port", System.getenv("PORT") ?: "8080")
    Micronaut.run(Endpoints::class.java)
}
