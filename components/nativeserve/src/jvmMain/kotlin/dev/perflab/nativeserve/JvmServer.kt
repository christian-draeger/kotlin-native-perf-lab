package dev.perflab.nativeserve

import dev.perflab.nativeserve.transport.openNioTransport

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
) {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val backend = System.getenv("NATIVESERVE_BACKEND") ?: "nio"
    require(backend == "nio") { "Unsupported NativeServe backend: $backend" }
    val routes = Routes().apply(configure)
    val transport = openNioTransport(host, port, limits.connections)
    try {
        println("NativeServe backend=$backend tcp=ipv4")
        HttpServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}
