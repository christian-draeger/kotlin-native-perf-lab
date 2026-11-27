@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.transport.openPlatformTransport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val backend = getenv("KTSERVE_BACKEND")?.toKString() ?: "kqueue"
    require(backend == "kqueue") { "Unsupported ktServe backend: $backend" }
    val routes = configuredRoutes(configure)
    routes.withServerExtensions {
        val transport = openPlatformTransport(host, port, limits.connections)
        try {
            println("ktServe backend=$backend tcp=ipv4")
            HttpServer(transport, routes, limits).run(stopRequested)
        } finally {
            transport.close()
        }
    }
}
