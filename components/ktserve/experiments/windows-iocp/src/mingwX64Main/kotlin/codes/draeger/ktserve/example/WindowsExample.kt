package codes.draeger.ktserve.example

import codes.draeger.ktserve.serve

fun iocpMain(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 8080
    val host = args.getOrNull(1) ?: "127.0.0.1"
    serve(host = host, port = port, configure = { parityRoutes() })
}
