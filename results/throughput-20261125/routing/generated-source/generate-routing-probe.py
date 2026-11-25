"""Generate a direct dispatcher for the 512-resource diagnostic only."""
from pathlib import Path
import sys

project = Path(sys.argv[1])
fixture_dir = project / "benchmarks/routing/kotlin/codes/draeger/ktserve/benchmark"
fixture_path = fixture_dir / "RoutingProbe.kt"
routing_path = project / "src/commonMain/kotlin/codes/draeger/ktserve/Routing.kt"

text = routing_path.read_text()
location = "    private val gets = MethodRoutes<GetHandler>()\n"
assert text.count(location) == 1
text = text.replace(location, location + "    internal var routingProbeDispatch: ((Request) -> Response?)? = null\n")
location = "        gets.resolve(request, action)"
assert text.count(location) == 1
routing_path.write_text(text.replace(location, "        routingProbeDispatch?.invoke(request) ?: gets.resolve(request, action)"))

text = fixture_path.read_text()
location = "        \"http\" -> serve(port = args[2].toInt()) {\n"
assert text.count(location) == 1
changed = text.replace(location, location + "            require(resources == 512) { \"The generated dispatch probe uses 512 resources\" }\n"
                       "            routingProbeDispatch = ::generatedRoutingProbeDispatch\n")
changed = changed.replace("private data class Item", "internal data class Item")
fixture_path.write_text(changed)

labels = "\n".join(f'        "r{number}" -> {number}' for number in range(512))
(fixture_dir / "GeneratedRoutingProbe.kt").write_text('''package codes.draeger.ktserve.benchmark

import codes.draeger.ktserve.Request
import codes.draeger.ktserve.Response
import codes.draeger.ktserve.json

// Generated from the route fixture. Each case is an exact literal, and normal
// dispatch handles paths and parameter values outside the specialization.
internal fun generatedRoutingProbeDispatch(request: Request): Response? {
    val path = request.path
    val prefix = "/api/v1/"
    if (!path.startsWith(prefix)) return null
    val segmentEnd = path.indexOf('/', prefix.length)
    val separator = "/items/"
    if (segmentEnd < 0 || !path.startsWith(separator, segmentEnd)) return null
    val resource = when (path.substring(prefix.length, segmentEnd)) {
'''+labels+'''
        else -> return null
    }
    val parameterStart = segmentEnd + separator.length
    val id = if (path.length - parameterStart == "current".length && path.startsWith("current", parameterStart)) {
        0
    } else {
        path.substring(parameterStart).toIntOrNull() ?: return null
    }
    return json(Item(resource, id))
}
''')
