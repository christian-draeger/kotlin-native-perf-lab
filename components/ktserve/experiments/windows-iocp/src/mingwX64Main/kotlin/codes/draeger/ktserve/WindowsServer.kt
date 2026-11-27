@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.probe.ns_backend_environment
import codes.draeger.ktserve.transport.openIocpTransport
import kotlinx.cinterop.toKString

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
) {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val backend = ns_backend_environment()?.toKString() ?: "iocp"
    require(backend == "iocp") { "Unsupported ktServe backend: $backend" }
    val routes = configuredRoutes(configure)
    routes.withServerExtensions {
        val transport = openIocpTransport(host, port, limits.connections)
        try {
            println("ktServe backend=$backend tcp=ipv4")
            HttpServer(transport, routes, limits).run(stopRequested)
        } finally {
            transport.close()
        }
    }
}
