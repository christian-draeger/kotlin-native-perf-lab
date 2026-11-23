package dev.perflab.examples.hello

import dev.perflab.nativeserve.serve
import dev.perflab.nativeserve.text

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    serve(port = port) {
        get("/") { text("Hello from NativeServe") }
        get("/health") { text("ok") }
    }
}
