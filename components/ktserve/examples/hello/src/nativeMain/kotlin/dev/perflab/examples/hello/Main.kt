package dev.perflab.examples.hello

import dev.perflab.ktserve.serve
import dev.perflab.ktserve.text

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    serve(port = port) {
        get("/") { text("Hello from ktServe") }
        get("/health") { text("ok") }
    }
}
