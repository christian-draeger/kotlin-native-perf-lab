@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package codes.draeger.ktserve.ktor.example

import codes.draeger.ktserve.ktor.KtServe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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

@Serializable
private data class Catalog(val id: Int, val items: List<CatalogItem>)

@Serializable
private data class CatalogItem(val sku: String, val name: String, val description: String, val stock: Stock)

@Serializable
private data class Stock(val available: Boolean, val units: Int)

internal fun Application.exampleModule(stop: () -> Unit = {}) {
    val suspended = AtomicInt(0)
    install(ContentNegotiation) { json() }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Example", "same-ktor-application")
    }
    routing {
        get("/health") { call.respondText("ok") }
        get("/json") { call.respond(User(42, "benchmark-user", true)) }
        get("/catalog/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respond(Catalog(id, List(32) { index ->
                    CatalogItem(
                        "$id-$index", "Café item $index",
                        "Roasted in München and packed for delivery.", Stock(index > 0, index * 3),
                    )
                }))
            }
        }
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
        get("/failure") { error("fixture handler failure") }
        get("/stop") {
            call.respondText("ok")
            stop()
        }
    }
}

fun main(args: Array<String>) = runBlocking {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val engine = args.getOrNull(1) ?: "ktserve"
    val stop = AtomicBoolean(false)
    val module: Application.() -> Unit = { exampleModule { stop.store(true) } }
    val server = when (engine) {
        "ktserve" -> embeddedServer(KtServe, port = port, host = "127.0.0.1", module = module)
        "cio" -> embeddedServer(CIO, port = port, host = "127.0.0.1", module = module)
        else -> error("Expected ktserve or cio")
    }
    server.startSuspend()
    try {
        while (!stop.load()) delay(25)
    } finally {
        server.stopSuspend(500, 5_000)
    }
}
