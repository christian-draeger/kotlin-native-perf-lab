@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.perflab.nativeserve.ktor.example

import dev.perflab.nativeserve.ktor.NativeServe
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt

@Serializable
private data class User(val id: Int, val name: String, val active: Boolean)

internal fun Application.exampleModule(stop: () -> Unit = {}) {
    val suspended = AtomicInt(0)
    install(ContentNegotiation) { json() }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Example", "same-ktor-application")
    }
    routing {
        get("/health") { call.respondText("ok") }
        get("/json") { call.respond(User(42, "benchmark-user", true)) }
        post("/upload") {
            val input = call.receiveChannel()
            val bytes = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.readAvailable(bytes, 0, bytes.size)
                if (count < 0) break
                total += count
            }
            call.respondText(total.toString())
        }
        get("/download") {
            call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
                writeFully(ByteArray(16 * 1024) { 's'.code.toByte() })
                flush()
                delay(50)
                writeFully(ByteArray(16 * 1024 + 3) { 't'.code.toByte() })
            }
        }
        get("/cancel") {
            suspended.addAndFetch(1)
            try {
                awaitCancellation()
            } finally {
                suspended.addAndFetch(-1)
            }
        }
        get("/suspended") { call.respondText(suspended.load().toString()) }
        get("/stop") {
            call.respondText("ok")
            stop()
        }
    }
}

fun main(args: Array<String>) = runBlocking {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val engine = args.getOrNull(1) ?: "nativeserve"
    val stop = AtomicBoolean(false)
    val module: Application.() -> Unit = { exampleModule { stop.store(true) } }
    val server = when (engine) {
        "nativeserve" -> embeddedServer(NativeServe, port = port, host = "127.0.0.1", module = module)
        "cio" -> embeddedServer(CIO, port = port, host = "127.0.0.1", module = module)
        else -> error("Expected nativeserve or cio")
    }
    server.startSuspend()
    try {
        while (!stop.load()) delay(25)
    } finally {
        server.stopSuspend(500, 5_000)
    }
}
