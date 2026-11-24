@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve

import dev.perflab.nativeserve.probe.ns_backend_environment
import dev.perflab.nativeserve.transport.openIocpTransport
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
    require(backend == "iocp") { "Unsupported NativeServe backend: $backend" }
    val routes = Routes().apply(configure)
    val transport = openIocpTransport(host, port, limits.connections)
    try {
        println("NativeServe backend=$backend tcp=ipv4")
        HttpServer(transport, routes, limits).run(stopRequested)
    } finally {
        transport.close()
    }
}
