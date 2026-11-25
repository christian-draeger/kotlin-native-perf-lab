package codes.draeger.examples.rest

import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.badRequest
import codes.draeger.ktserve.json
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class Update(val message: String)

@Serializable
private data class Item(val id: Int, val message: String)

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8081
    val writer = requireNotNull(System.getenv("KTSERVE_REST_USER")) { "Set KTSERVE_REST_USER" }
    require(writer.isNotBlank()) { "KTSERVE_REST_USER must not be blank" }
    val stop = AtomicBoolean()
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        stop.set(true)
        stopped.await(3, TimeUnit.SECONDS)
    })
    try {
        // The private loopback origin accepts an identity asserted by the local TLS ingress.
        serve(port = port, host = "127.0.0.1", limits = ServerLimits(requestBodyBytes = 4096), stopRequested = stop::get) {
            beforeEach { request ->
                val identity = request.headers().singleOrNull { it.name.equals("X-KtServe-Identity", ignoreCase = true) }
                if (request.path == "/health" || identity?.value == writer) null else text("Forbidden", status = 403)
            }
            get("/health") { text("ok") }
            route("PUT", "/items/{id}") { request, body ->
                val id = request.pathInt("id") ?: return@route badRequest()
                val bytes = ByteArrayOutputStream()
                while (true) {
                    bytes.write(body.readChunk() ?: break)
                }
                val update = try {
                    Json.decodeFromString<Update>(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
                } catch (_: SerializationException) {
                    return@route badRequest()
                } catch (_: CharacterCodingException) {
                    return@route badRequest()
                }
                json(Item(id, update.message))
            }
        }
    } finally {
        stopped.countDown()
    }
}
