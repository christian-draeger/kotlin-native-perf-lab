package codes.draeger.ktserve.example

import codes.draeger.benchmark.upstream.UpstreamClient
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun runUpstreamProbe() {
    val port = getenv("PORT")?.toKString()?.toInt() ?: 8080
    val originPort = requireNotNull(getenv("BENCHMARK_UPSTREAM_PORT")?.toKString()?.toInt())
    val client = UpstreamClient(originPort)
    try {
        serve(port = port, host = "127.0.0.1") {
            parityRoutes()
            getSuspending("/upstream/{scenario}") { request ->
                val result = client.request(requireNotNull(request.pathParameter("scenario")))
                text(result.message, result.status)
            }
        }
    } finally {
        client.close()
    }
}
