package io.github.christian_draeger

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

data class User(val id: Int, val name: String, val active: Boolean)

@Path("/")
open class Application {
    @GET
    @Path("health")
    @Produces(MediaType.TEXT_PLAIN)
    fun health() = "ok"

    @GET
    @Path("json")
    @Produces(MediaType.APPLICATION_JSON)
    fun json() = User(42, "benchmark-user", true)

    @GET
    @Path("users/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    fun user(@PathParam("id") id: String): Response {
        val userId = id.toIntOrNull() ?: return Response.status(Response.Status.BAD_REQUEST).build()
        return Response.ok(User(userId, "benchmark-user", true)).build()
    }
}
