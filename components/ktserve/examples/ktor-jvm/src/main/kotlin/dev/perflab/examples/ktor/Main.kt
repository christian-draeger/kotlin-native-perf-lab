package dev.perflab.examples.ktor

import dev.perflab.ktserve.ktor.KtServe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    embeddedServer(KtServe, host = "127.0.0.1", port = args.firstOrNull()?.toInt() ?: 8080) {
        routing { get("/health") { call.respondText("ok") } }
    }.start(wait = true)
}
