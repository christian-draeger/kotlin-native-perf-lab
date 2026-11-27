import codes.draeger.ktserve.badRequest
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    serve(port = port) {
        get("/users/{id}") { request ->
            val id = request.pathInt("id") ?: return@get badRequest()
            text("user-$id")
        }
    }
}
