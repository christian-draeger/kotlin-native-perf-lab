package dev.perflab.nativeserve

class Routes {
    private val gets = MethodRoutes<GetHandler>()
    private val posts = MethodRoutes<(Request) -> RequestBodySink>()
    internal var hasSuspendingGets = false
        private set
    internal var applicationHandler: (suspend (Request, RequestContent) -> Response)? = null
        private set

    fun get(path: String, handler: (Request) -> Response) {
        require(applicationHandler == null) { "A standalone application owns all routes" }
        gets.add(path, GetHandler.Immediate(handler))
    }

    /**
     * Run a suspending HTTP/1.x GET outside the I/O loop. Handlers can run concurrently.
     * A disconnect, timeout, or shutdown cancels the handler's coroutine.
     */
    fun getSuspending(path: String, handler: suspend (Request) -> Response) {
        require(applicationHandler == null) { "A standalone application owns all routes" }
        gets.add(path, GetHandler.Suspending(handler))
        hasSuspendingGets = true
    }

    /** Open one body sink per POST. HTTP/1.1 accepts chunked bodies. */
    fun postStreaming(path: String, handler: (Request) -> RequestBodySink) {
        require(applicationHandler == null) { "A standalone application owns all routes" }
        posts.add(path, handler)
    }

    /**
     * Give an HTTP/1.x application all requests on this listener. The application supplies routing.
     * Read the body incrementally and return a response. One exchange is active per connection.
     */
    fun application(handler: suspend (Request, RequestContent) -> Response) {
        require(applicationHandler == null && gets.isEmpty() && posts.isEmpty()) {
            "An application handler must own all routes on its listener"
        }
        applicationHandler = handler
    }

    internal fun respond(request: Request): Response = checkNotNull(respond(request, null))

    internal fun respond(
        request: Request,
        start: ((suspend () -> Response) -> Unit)?,
    ): Response? {
        return try {
            if (request.method == "GET") {
                gets.resolve(request) { handler, matched ->
                    when (handler) {
                        is GetHandler.Immediate -> return handler.run(matched)
                        is GetHandler.Suspending -> {
                            if (start == null) return emptyResponse(501)
                            start { handler.run(matched) }
                            return null
                        }
                    }
                }
                if (posts.matches(request)) methodNotAllowed("POST") else emptyResponse(404)
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
    }

    internal fun openPost(request: Request): PostBody = try {
        val sink = posts.resolve(request) { handler, matched -> handler(matched) }
        if (sink != null) PostBody.Open(sink)
        else PostBody.Rejected(emptyResponse(if (gets.matches(request) || posts.isEmpty()) 405 else 404))
    } catch (_: Exception) {
        PostBody.Rejected(emptyResponse(500))
    }
}

private sealed interface GetHandler {
    class Immediate(val run: (Request) -> Response) : GetHandler
    class Suspending(val run: suspend (Request) -> Response) : GetHandler
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
