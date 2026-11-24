package io.github.christian_draeger

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext

data class User(val id: Int, val name: String, val active: Boolean)

private fun RoutingContext.sendUser(id: Int) {
    response().putHeader("Content-Type", "application/json")
        .end(Json.encode(User(id, "benchmark-user", true)))
}

fun main() {
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val vertx = Vertx.vertx()
    val router = Router.router(vertx)
    router.get("/health").handler { it.response().putHeader("Content-Type", "text/plain").end("ok") }
    router.get("/json").handler { it.sendUser(42) }
    router.get("/users/:id").handler { ctx ->
        val id = ctx.pathParam("id").toIntOrNull()
        if (id == null) ctx.response().setStatusCode(400).end() else ctx.sendUser(id)
    }
    vertx.createHttpServer().requestHandler(router).listen(port, host)
        .toCompletionStage().toCompletableFuture().join()
}
