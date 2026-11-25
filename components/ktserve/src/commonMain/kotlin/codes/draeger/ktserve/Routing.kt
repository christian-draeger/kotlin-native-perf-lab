package codes.draeger.ktserve

internal fun configuredRoutes(configure: Routes.() -> Unit): Routes =
    Routes().apply(configure).also(Routes::prepare)

class Routes {
    private val gets = MethodRoutes<GetHandler>()
    private val posts = MethodRoutes<(Request) -> RequestBodySink>()
    private var requestRoutes: MutableMap<String, MethodRoutes<RequestHandler>>? = null
    internal val hasRequestRoutes: Boolean get() = requestRoutes != null
    internal var hasRequestExtensions = false
        private set
    internal var requestPolicy: ((Request) -> Response?)? = null
        private set
    internal var hasSuspendingGets = false
        private set
    internal var hasBlockingGets = false
        private set
    internal var applicationHandler: (suspend (Request, RequestContent) -> Response)? = null
        private set
    internal var observer: ServerObserver? = null
        private set

    internal fun prepare() {
        gets.prepare()
        posts.prepare()
        requestRoutes?.values?.forEach { it.prepare() }
    }

    /** Install the event sink for this HTTP/1.x listener. */
    fun observe(observer: ServerObserver) {
        check(this.observer == null) { "An observer is already configured" }
        this.observer = observer
    }

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
        hasRequestExtensions = true
    }

    /**
     * Run an early HTTP/1.x header policy before routing or reading the request body.
     * Return null to continue, or a response to end this exchange.
     */
    fun beforeEach(policy: (Request) -> Response?) {
        val previous = requestPolicy
        requestPolicy = if (previous == null) policy else { request -> previous(request) ?: policy(request) }
        hasRequestExtensions = true
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

    internal inline fun dispatchGet(request: Request, action: (GetHandler, Request) -> Response): Response? =
        gets.resolve(request, action)

    internal fun respond(request: Request): Response = try {
        val response = if (request.method == "GET") {
            dispatchGet(request) { handler, matched ->
                when (handler) {
                    is GetHandler.Immediate -> handler.run(matched)
                    else -> emptyResponse(501)
                }
            }
        } else null
        response ?: legacyMethodError(request)
    } catch (_: Exception) {
        emptyResponse(500)
    }

    internal fun http1MethodError(request: Request): Response {
        val allowed = allowedMethods(request) ?: return emptyResponse(404)
        return if (request.method == "OPTIONS") {
            emptyResponse(204).withHeaders(HttpField("Allow", allowed))
        } else methodNotAllowed(allowed)
    }

    internal fun openPost(request: Request, http1Methods: Boolean = false): PostBody = try {
        val sink = posts.resolve(request) { handler, matched -> handler(matched) }
        if (sink != null) {
            PostBody.Open(sink)
        } else if (http1Methods) {
            PostBody.Rejected(allowedMethods(request)?.let(::methodNotAllowed) ?: emptyResponse(404))
        } else {
            PostBody.Rejected(emptyResponse(if (gets.matches(request) || posts.isEmpty()) 405 else 404))
        }
    } catch (_: Exception) {
        PostBody.Rejected(emptyResponse(500))
    }

    private fun legacyMethodError(request: Request): Response {
        val post = posts.matches(request)
        if (request.method == "GET") return if (post) methodNotAllowed("POST") else emptyResponse(404)
        return when {
            post && gets.matches(request) -> methodNotAllowed("GET, POST")
            post -> methodNotAllowed("POST")
            else -> emptyResponse(405)
        }
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

internal sealed interface GetHandler {
    class Immediate(val run: (Request) -> Response) : GetHandler
    class Suspending(val run: suspend (Request) -> Response) : GetHandler
    class Blocking(val run: (Request) -> Response) : GetHandler
}

internal sealed interface PostBody {
    class Open(val sink: RequestBodySink) : PostBody
    class Rejected(val response: Response) : PostBody
}

internal class MethodRoutes<Handler> {
    private val exact = mutableMapOf<String, Handler>()
    private val patterns = mutableListOf<RoutePattern<Handler>>()
    private var patternsBySize: Map<Int, PatternGroup<Handler>>? = null

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
        patternsBySize = null
    }

    fun prepare() {
        if (patterns.size < 8) return
        val groups = patterns.groupBy { it.shape.size }.mapValues { (_, routes) -> PatternGroup(routes) }
        patternsBySize = groups.takeIf { it.size > 1 || it.values.first().partitioned }
    }

    fun isEmpty(): Boolean = exact.isEmpty() && patterns.isEmpty()

    fun matches(request: Request): Boolean = resolve(request) { _, _ -> true } == true

    inline fun <Result> resolve(request: Request, action: (Handler, Request) -> Result): Result? {
        exact[request.path]?.let { return action(it, request) }
        if (patterns.isEmpty()) return null
        val groups = patternsBySize
        val candidates = if (groups == null) patterns else {
            val parts = request.path.removePrefix("/").split('/')
            groups[parts.size]?.candidates(parts) ?: return null
        }
        for (route in candidates) {
            val parameters = route.parameters(request.path) ?: continue
            return action(route.handler, request.withParameters(parameters))
        }
        return null
    }

}

internal class PatternGroup<Handler>(private val routes: List<RoutePattern<Handler>>) {
    // A fixed segment shared by every route as a literal partitions the list
    // without changing the declaration order of routes that can match.
    private val position = routes.first().shape.indices
        .filter { index -> routes.all { it.shape[index] != null } }
        .maxByOrNull { index -> routes.map { it.shape[index] }.toSet().size }
    private val literals = position?.let { index -> routes.groupBy { it.shape[index] }.takeIf { it.size > 1 } }
    val partitioned: Boolean get() = literals != null

    fun candidates(parts: List<String>): List<RoutePattern<Handler>> =
        if (position == null || literals == null) routes else literals[parts[position]].orEmpty()
}

internal data class RoutePattern<Handler>(
    val shape: List<String?>,
    val names: List<String?>,
    val handler: Handler,
) {
    fun parameters(path: String): Map<String, String>? {
        var start = if (path.startsWith('/')) 1 else 0
        var captured: MutableMap<String, String>? = null
        for (index in shape.indices) {
            val separator = path.indexOf('/', start)
            if ((separator < 0) != (index == shape.lastIndex)) return null
            val end = if (separator < 0) path.length else separator
            val literal = shape[index]
            if (literal == null) {
                if (end == start) return null
                val parameters = captured ?: mutableMapOf<String, String>().also { captured = it }
                parameters[checkNotNull(names[index])] = path.substring(start, end)
            } else if (end - start != literal.length || !path.regionMatches(start, literal, 0, literal.length)) {
                return null
            }
            start = end + 1
        }
        return captured
    }
}
