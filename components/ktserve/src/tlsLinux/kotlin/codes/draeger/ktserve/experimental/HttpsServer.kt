@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.experimental

import codes.draeger.ktserve.HttpServer
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.configuredRoutes
import codes.draeger.ktserve.openssl.ktserve_tls_context
import codes.draeger.ktserve.openssl.ktserve_tls_free_context
import codes.draeger.ktserve.transport.openTlsTransport
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.SIG_ERR
import platform.posix.SIG_IGN
import platform.posix.SIGPIPE
import platform.posix.signal

/** Experimental Linux CLI entry point. Owns the process-wide SIGPIPE disposition while serving. */
fun serveHttps(
    port: Int,
    host: String,
    certificate: String,
    privateKey: String,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val loaded = alloc<COpaquePointerVar> { value = null }
    when (ktserve_tls_context(certificate, privateKey, loaded.ptr)) {
        0 -> Unit
        2 -> error("Could not load the TLS certificate chain")
        3 -> error("Could not load a TLS private key matching the certificate")
        else -> error("Could not initialize OpenSSL")
    }
    val context = checkNotNull(loaded.value)
    try {
        val routes = configuredRoutes(configure)
        val previous = signal(SIGPIPE, SIG_IGN)
        check(previous != SIG_ERR) { "Could not ignore SIGPIPE for the TLS listener" }
        try {
            routes.withServerExtensions {
                val transport = openTlsTransport(host, port, limits.connections, context)
                try {
                    HttpServer(transport, routes, limits).run(stopRequested)
                } finally {
                    transport.close()
                }
            }
        } finally {
            signal(SIGPIPE, previous)
        }
    } finally {
        ktserve_tls_free_context(context)
    }
}
