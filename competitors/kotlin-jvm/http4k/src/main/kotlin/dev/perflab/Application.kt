package dev.perflab

import org.eclipse.jetty.server.ServerConnector
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.format.Jackson.json
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.Jetty
import org.http4k.server.asServer

data class User(val id: Int, val name: String, val active: Boolean)

private fun user(id: Int) = Response(OK).json(User(id, "benchmark-user", true))

fun main() {
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val app = routes(
        "/health" bind GET to { Response(OK).header("Content-Type", "text/plain").body("ok") },
        "/json" bind GET to { user(42) },
        "/users/{id}" bind GET to { request ->
            val id = request.path("id")?.toIntOrNull()
            if (id == null) Response(BAD_REQUEST) else user(id)
        },
    )
    app.asServer(Jetty(port, { server ->
        ServerConnector(server).apply {
            setHost(host)
            setPort(port)
        }
    })).start()
}
