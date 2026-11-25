package codes.draeger.examples.hello

import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val stop = AtomicBoolean()
    val stopped = CountDownLatch(1)
    val telemetry = if (System.getenv("KTSERVE_OBSERVE") == "1") ConsoleObserver() else null
    Runtime.getRuntime().addShutdownHook(Thread {
        stop.set(true)
        stopped.await(3, TimeUnit.SECONDS)
    })
    try {
        serve(port = port, stopRequested = stop::get) {
            if (telemetry != null) {
                observe(telemetry)
                get("/ready") {
                    if (telemetry.ready.get()) text("ready") else text("draining", status = 503)
                }
            }
            get("/") { text("Hello from ktServe on the JVM") }
            get("/health") { text("ok") }
        }
    } finally {
        telemetry?.close()
        stopped.countDown()
    }
}
