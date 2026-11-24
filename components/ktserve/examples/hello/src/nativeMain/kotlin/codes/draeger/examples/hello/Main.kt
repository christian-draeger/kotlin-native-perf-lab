package codes.draeger.examples.hello

import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    serve(port = port) {
        get("/") { text("Hello from ktServe") }
        get("/health") { text("ok") }
    }
}
