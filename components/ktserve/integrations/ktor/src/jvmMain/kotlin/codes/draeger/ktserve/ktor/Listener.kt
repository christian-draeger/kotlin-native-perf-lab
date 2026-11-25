package codes.draeger.ktserve.ktor

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.serve

internal actual fun serveListener(
    port: Int,
    host: String,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
    onListening: () -> Unit,
    configure: Routes.() -> Unit,
) = serve(port, host, limits, stopRequested, onListening, configure)
