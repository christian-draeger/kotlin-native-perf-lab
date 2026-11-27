@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.cli.staticfiles

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.cli.signals.ktserve_cli_install_signals
import codes.draeger.ktserve.cli.signals.ktserve_cli_restore_signals
import codes.draeger.ktserve.cli.signals.ktserve_cli_stop_requested
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.staticFiles
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.exit
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.stderr

private const val USAGE = "Usage: ktserve serve [<directory>] [--mount <prefix> <directory>]... " +
    "[--cache-control <value>] [--access-log] [--tls-cert <chain.pem> --tls-key <key.pem>]"

fun main(args: Array<String>) {
    if (args.size == 1 && args[0] == "--help") {
        println(USAGE)
        return
    }
    val options = if (args.firstOrNull() == "serve") parseOptions(args.drop(1)) else null
    if (options == null) {
        fputs("$USAGE\n", stderr)
        exit(2)
        return
    }
    try {
        val configuredPort = getenv("PORT")?.toKString() ?: "8080"
        val port = configuredPort.toIntOrNull()
        require(port != null && port in 1..65535) { "PORT must be an integer between 1 and 65535" }
        val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
        val scheme = if (options.tls == null) "http" else "https"
        check(ktserve_cli_install_signals() == 0) { "Could not install signal handlers (errno $errno)" }
        try {
            println("ktserve: starting at $scheme://$host:$port")
            val limits = ServerLimits(responseBodyBytes = 1024 * 1024)
            val stopRequested = { ktserve_cli_stop_requested() != 0 }
            withAccessLog(options.accessLog) { observer ->
                val configure: Routes.() -> Unit = {
                    if (observer != null) observe(observer)
                    for ((at, directory, cacheControl) in options.mounts) staticFiles(directory, at, cacheControl)
                }
                val tls = options.tls
                if (tls == null) {
                    serve(port = port, host = host, limits = limits, stopRequested = stopRequested, configure = configure)
                } else {
                    serveWithHttps(port, host, tls, limits, stopRequested, configure)
                }
            }
        } finally {
            ktserve_cli_restore_signals()
        }
    } catch (failure: Exception) {
        fputs("ktserve: ${failure.message}\n", stderr)
        exit(1)
    }
}

private data class Mount(val at: String, val directory: String, val cacheControl: String? = null)

internal data class TlsFiles(val certificate: String, val key: String)

private data class Options(val mounts: List<Mount>, val accessLog: Boolean, val tls: TlsFiles?)

private fun parseOptions(args: List<String>): Options? {
    val mounts = mutableListOf<Mount>()
    var certificate: String? = null
    var key: String? = null
    var accessLog = false
    var rootDirectory = false
    var index = 0
    while (index < args.size) {
        when (val argument = args[index++]) {
            "--mount" -> {
                if (index + 1 >= args.size) return null
                mounts.add(Mount(args[index], args[index + 1]))
                index += 2
            }
            "--cache-control" -> {
                if (index == args.size || mounts.isEmpty() || mounts.last().cacheControl != null) return null
                mounts[mounts.lastIndex] = mounts.last().copy(cacheControl = args[index++])
            }
            "--access-log" -> accessLog = true
            "--tls-cert" -> {
                if (certificate != null || index == args.size || args[index].startsWith("--")) return null
                certificate = args[index++]
            }
            "--tls-key" -> {
                if (key != null || index == args.size || args[index].startsWith("--")) return null
                key = args[index++]
            }
            else -> {
                if (rootDirectory || argument.startsWith('-')) return null
                mounts.add(Mount("/", argument))
                rootDirectory = true
            }
        }
    }
    if (mounts.isEmpty() || (certificate == null) != (key == null)) return null
    val tls = if (certificate == null) null else TlsFiles(certificate, checkNotNull(key))
    return Options(mounts, accessLog, tls)
}
