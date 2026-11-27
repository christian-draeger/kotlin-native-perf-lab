@file:Suppress("UNUSED_PARAMETER")

package codes.draeger.ktserve.cli.staticfiles

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits

internal fun serveWithHttps(
    port: Int,
    host: String,
    tls: TlsFiles,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
    configure: Routes.() -> Unit,
): Unit = error("This executable was built without HTTPS. Build on Linux with -Pktserve.opensslRoot=<prefix>")
