package codes.draeger.ktserve

import codes.draeger.ktserve.transport.openNioTransport

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
) = serve(port, host, limits, stopRequested, onListening = {}, configure = configure)

/** Serve HTTP and notify the caller after the listener has successfully bound. */
fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    onListening: () -> Unit,
    configure: Routes.() -> Unit,
) {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val backend = System.getenv("KTSERVE_BACKEND") ?: "nio"
    require(backend == "nio") { "Unsupported ktServe backend: $backend" }
    val routes = configuredRoutes(configure)
    routes.withServerExtensions {
        val transport = openNioTransport(host, port, limits.connections)
        try {
            println("ktServe backend=$backend tcp=ipv4")
            onListening()
            HttpServer(transport, routes, limits).run(stopRequested)
        } finally {
            transport.close()
        }
    }
}
