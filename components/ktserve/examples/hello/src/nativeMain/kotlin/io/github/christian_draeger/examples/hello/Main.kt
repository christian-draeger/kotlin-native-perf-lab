package io.github.christian_draeger.examples.hello

import io.github.christian_draeger.ktserve.serve
import io.github.christian_draeger.ktserve.text

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    serve(port = port) {
        get("/") { text("Hello from ktServe") }
        get("/health") { text("ok") }
    }
}
