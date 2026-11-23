package dev.perflab.nativeserve

class Routes {
    private val exact = mutableMapOf<String, (Request) -> Response>()
    private val patterns = mutableListOf<RoutePattern>()

    fun get(path: String, handler: (Request) -> Response) {
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

    internal fun respond(request: Request): Response = try {
        dispatch(request)
    } catch (_: Exception) {
        emptyResponse(500)
    }

    private fun dispatch(request: Request): Response {
        if (request.method != "GET") return emptyResponse(405)
        exact[request.path]?.let { return it(request) }
        if (patterns.isEmpty()) return emptyResponse(404)
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
            if (matches) return route.handler(request.withParameters(values))
        }
        return emptyResponse(404)
    }

    private data class RoutePattern(
        val shape: List<String?>,
        val names: List<String?>,
        val handler: (Request) -> Response,
    )
}
