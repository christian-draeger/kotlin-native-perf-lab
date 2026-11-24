@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.christian_draeger.ktserve.example

import io.github.christian_draeger.ktserve.serve
import io.github.christian_draeger.ktserve.transport.UringUnavailable
import io.github.christian_draeger.ktserve.transport.probeUring
import kotlinx.cinterop.memScoped
import kotlin.system.exitProcess

fun uringMain(args: Array<String>) {
    if (args.singleOrNull() == "--capabilities") {
        try {
            val features = memScoped { probeUring() }
            println("io_uring available features=0x${features.toString(16)}")
        } catch (failure: UringUnavailable) {
            println(failure.message)
            exitProcess(77)
        }
        return
    }
    val port = args.getOrNull(0)?.toInt() ?: 8080
    val host = args.getOrNull(1) ?: "127.0.0.1"
    serve(host = host, port = port, configure = { parityRoutes() })
}
