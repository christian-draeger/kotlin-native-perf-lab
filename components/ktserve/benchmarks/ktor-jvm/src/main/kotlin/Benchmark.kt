package io.github.christian_draeger.ktserve.ktor.example

import io.github.christian_draeger.ktserve.ktor.KtServe
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    val port = args[0].toInt()
    val engine = args[1]
    val host = "127.0.0.1"
    val module: Application.() -> Unit = { exampleModule() }
    val server = when (engine) {
        "cio" -> embeddedServer(CIO, port = port, host = host, module = module)
        "netty" -> embeddedServer(Netty, port = port, host = host, module = module)
        "ktserve" -> embeddedServer(KtServe, port = port, host = host, module = module)
        else -> error("Expected cio, netty or ktserve")
    }
    server.start(wait = true)
}
