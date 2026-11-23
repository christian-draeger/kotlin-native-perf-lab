package dev.perflab

import io.javalin.Javalin

data class User(val id: Int, val name: String, val active: Boolean)

fun main() {
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toInt() ?: 8080
    Javalin.create { config ->
        config.jetty.host = host
        config.jetty.port = port
        config.routes.get("/health") { it.contentType("text/plain").result("ok") }
        config.routes.get("/json") { it.json(User(42, "benchmark-user", true)) }
        config.routes.get("/users/{id}") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull()
            if (id == null) ctx.status(400) else ctx.json(User(id, "benchmark-user", true))
        }
    }.start()
}
