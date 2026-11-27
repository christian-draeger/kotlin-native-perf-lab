package codes.draeger.ktserve.cli.staticfiles

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.experimental.serveHttps

internal fun serveWithHttps(
    port: Int,
    host: String,
    tls: TlsFiles,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
    configure: Routes.() -> Unit,
) = serveHttps(port, host, tls.certificate, tls.key, limits, stopRequested, configure)
