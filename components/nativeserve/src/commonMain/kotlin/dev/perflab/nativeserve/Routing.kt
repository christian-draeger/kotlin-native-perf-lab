package dev.perflab.nativeserve

class Routes {
    private val gets = MethodRoutes<(Request) -> Response>()
    private val posts = MethodRoutes<(Request) -> RequestBodySink>()

    fun get(path: String, handler: (Request) -> Response) {
        gets.add(path, handler)
    }

    /** Open one body sink per POST. HTTP/1.1 accepts chunked bodies. */
    fun postStreaming(path: String, handler: (Request) -> RequestBodySink) {
        posts.add(path, handler)
    }

    internal fun respond(request: Request): Response = try {
        if (request.method == "GET") {
            gets.resolve(request) { handler, matched -> handler(matched) }
                ?: if (posts.matches(request)) methodNotAllowed("POST") else emptyResponse(404)
        } else {
            val get = gets.matches(request)
            val post = posts.matches(request)
            when {
                get && post -> methodNotAllowed("GET, POST")
                post -> methodNotAllowed("POST")
                else -> emptyResponse(405)
            }
        }
    } catch (_: Exception) {
        emptyResponse(500)
    }

    internal fun openPost(request: Request): PostBody = try {
        val sink = posts.resolve(request) { handler, matched -> handler(matched) }
        if (sink != null) PostBody.Open(sink)
        else PostBody.Rejected(emptyResponse(if (gets.matches(request) || posts.isEmpty()) 405 else 404))
    } catch (_: Exception) {
        PostBody.Rejected(emptyResponse(500))
    }
}

internal sealed interface PostBody {
    class Open(val sink: RequestBodySink) : PostBody
    class Rejected(val response: Response) : PostBody
}

private class MethodRoutes<Handler> {
    private val exact = mutableMapOf<String, Handler>()
    private val patterns = mutableListOf<RoutePattern<Handler>>()

    fun add(path: String, handler: Handler) {
        require(path.startsWith('/') && '?' !in path && '#' !in path) { "Expected an absolute route path" }
        val parts = path.removePrefix("/").split('/')
        val names = parts.map { part ->
            if ('{' in part || '}' in part) {
                val name = part.removePrefix("{").removeSuffix("}")
                require(part == "{$name}" && name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) {
                    "Route parameters must occupy an entire path segment"
                }
                name
            } else {
                null
            }
        }
        val parameters = names.filterNotNull()
        if (parameters.isEmpty()) {
            require(path !in exact) { "Duplicate route $path" }
            exact[path] = handler
            return
        }
        require(parameters.size == parameters.toSet().size) { "Repeated parameter in $path" }
        val shape = parts.mapIndexed { index, part -> part.takeIf { names[index] == null } }
        require(patterns.none { it.shape == shape }) { "Duplicate route pattern $path" }
        patterns.add(RoutePattern(shape, names, handler))
    }

    fun isEmpty(): Boolean = exact.isEmpty() && patterns.isEmpty()

    fun matches(request: Request): Boolean = resolve(request) { _, _ -> true } == true

    inline fun <Result> resolve(request: Request, action: (Handler, Request) -> Result): Result? {
        exact[request.path]?.let { return action(it, request) }
        if (patterns.isEmpty()) return null
        val parts = request.path.removePrefix("/").split('/')
        for (route in patterns) {
            if (parts.size != route.shape.size) continue
            val values = mutableMapOf<String, String>()
            var matches = true
            for (index in parts.indices) {
                val literal = route.shape[index]
                val value = parts[index]
                if (literal != null && literal != value || literal == null && value.isEmpty()) {
                    matches = false
                    break
                }
                route.names[index]?.let { values[it] = value }
            }
            if (matches) return action(route.handler, request.withParameters(values))
        }
        return null
    }

}

private data class RoutePattern<Handler>(
    val shape: List<String?>,
    val names: List<String?>,
    val handler: Handler,
)
