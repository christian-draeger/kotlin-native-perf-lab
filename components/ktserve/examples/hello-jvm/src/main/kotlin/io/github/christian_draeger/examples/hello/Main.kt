package io.github.christian_draeger.examples.hello

import io.github.christian_draeger.ktserve.serve
import io.github.christian_draeger.ktserve.text
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val stop = AtomicBoolean()
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        stop.set(true)
        stopped.await(3, TimeUnit.SECONDS)
    })
    try {
        serve(port = port, stopRequested = stop::get) {
            get("/") { text("Hello from ktServe on the JVM") }
            get("/health") { text("ok") }
        }
    } finally {
        stopped.countDown()
    }
}
