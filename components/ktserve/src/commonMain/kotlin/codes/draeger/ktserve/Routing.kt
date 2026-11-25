package codes.draeger.ktserve

class Routes {
    private val gets = MethodRoutes<GetHandler>()
    private val posts = MethodRoutes<(Request) -> RequestBodySink>()
    private var requestRoutes: MutableMap<String, MethodRoutes<RequestHandler>>? = null
    internal val hasRequestRoutes: Boolean get() = requestRoutes != null
    internal var requestPolicy: ((Request) -> Response?)? = null
        private set
    internal var hasSuspendingGets = false
        private set
    internal var hasBlockingGets = false
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

    /**
     * Offload a blocking HTTP/1.x GET. Admission is bounded by [ServerLimits.blockingHandlers].
     * A saturated dispatcher rejects the request with HTTP 503.
     */
    fun getBlocking(path: String, handler: (Request) -> Response) {
        require(applicationHandler == null) { "A standalone application owns all routes" }
        gets.add(path, GetHandler.Blocking(handler))
        hasBlockingGets = true
    }

    /** Open one body sink per POST. HTTP/1.1 accepts chunked bodies. */
    fun postStreaming(path: String, handler: (Request) -> RequestBodySink) {
        require(applicationHandler == null) { "A standalone application owns all routes" }
        posts.add(path, handler)
    }

    /**
     * Register an HTTP/1.x handler with bounded, cancellable body reads.
     * These routes precede the GET and streaming POST conveniences for the same method and path.
     */
    fun route(method: String, path: String, handler: suspend (Request, RequestContent) -> Response) {
        require(applicationHandler == null) { "A standalone application owns all routes" }
        require(method.isNotEmpty() && method.all(Char::isHttpToken) && method != "CONNECT") {
            "Expected an HTTP method for an ordinary request-response exchange"
        }
        val routes = requestRoutes ?: linkedMapOf<String, MethodRoutes<RequestHandler>>().also { requestRoutes = it }
        routes.getOrPut(method) { MethodRoutes() }.add(path, handler)
    }

    /**
     * Run an early header policy before routing or reading the request body.
     * Return null to continue, or a response to end this exchange.
     */
    fun beforeEach(policy: (Request) -> Response?) {
        val previous = requestPolicy
        requestPolicy = if (previous == null) policy else { request -> previous(request) ?: policy(request) }
    }

    internal fun dispatchRequestRoute(
        request: Request,
        action: (RequestHandler, Request) -> Boolean,
    ): Boolean? {
        val routes = requestRoutes ?: return null
        return routes[request.method]?.resolve(request, action)
            ?: if (request.method == "HEAD") routes["GET"]?.resolve(request, action) else null
    }

    /**
     * Give an HTTP/1.x application all requests on this listener. The application supplies routing.
     * Read the body incrementally and return a response. One exchange is active per connection.
     */
    fun application(handler: suspend (Request, RequestContent) -> Response) {
        require(applicationHandler == null && gets.isEmpty() && posts.isEmpty() && !hasRequestRoutes) {
            "An application handler must own all routes on its listener"
        }
        applicationHandler = handler
    }

    internal fun respond(request: Request): Response = checkNotNull(respond(request, null))

    internal fun respond(
        request: Request,
        start: ((suspend () -> Response) -> Unit)?,
        startBlocking: ((() -> Response) -> Boolean)? = null,
    ): Response? {
        return try {
            if (request.method == "GET" || request.method == "HEAD") {
                gets.resolve(request) { handler, matched ->
                    when (handler) {
                        is GetHandler.Immediate -> return handler.run(matched)
                        is GetHandler.Suspending -> {
                            if (start == null) return emptyResponse(501)
                            start { handler.run(matched) }
                            return null
                        }
                        is GetHandler.Blocking -> {
                            if (startBlocking == null) return emptyResponse(501)
                            if (!startBlocking { handler.run(matched) }) return emptyResponse(503)
                            return null
                        }
                    }
                }
            }
            val allowed = allowedMethods(request)
            when {
                allowed == null -> emptyResponse(404)
                request.method == "OPTIONS" -> emptyResponse(204).withHeaders(HttpField("Allow", allowed))
                else -> methodNotAllowed(allowed)
            }
        } catch (_: Exception) {
            emptyResponse(500)
        }
    }

    internal fun openPost(request: Request): PostBody = try {
        val sink = posts.resolve(request) { handler, matched -> handler(matched) }
        if (sink != null) PostBody.Open(sink)
        else PostBody.Rejected(allowedMethods(request)?.let(::methodNotAllowed) ?: emptyResponse(404))
    } catch (_: Exception) {
        PostBody.Rejected(emptyResponse(500))
    }

    private fun allowedMethods(request: Request): String? {
        val methods = linkedSetOf<String>()
        if (gets.matches(request)) methods.add("GET")
        if (posts.matches(request)) methods.add("POST")
        requestRoutes?.forEach { (method, routes) ->
            if (routes.matches(request)) methods.add(method)
        }
        if (methods.isEmpty()) return null
        if ("GET" in methods) methods.add("HEAD")
        methods.add("OPTIONS")
        return methods.joinToString(", ")
    }
}

internal typealias RequestHandler = suspend (Request, RequestContent) -> Response

private sealed interface GetHandler {
    class Immediate(val run: (Request) -> Response) : GetHandler
    class Suspending(val run: suspend (Request) -> Response) : GetHandler
    class Blocking(val run: (Request) -> Response) : GetHandler
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
