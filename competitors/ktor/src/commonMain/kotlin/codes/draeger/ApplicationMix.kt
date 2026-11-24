package codes.draeger

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay

private const val UPLOAD_LIMIT = 64 * 1024

internal fun Route.applicationMixRoutes() {
    post("/upload") {
        val input = call.receiveChannel()
        val buffer = ByteArray(4096)
        var total = 0
        var valid = true
        while (true) {
            val count = input.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            total += count
            if (total > UPLOAD_LIMIT) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            valid = valid && (0 until count).all { buffer[it] == 'x'.code.toByte() }
        }
        if (valid) call.respondText(total.toString()) else call.respond(HttpStatusCode.BadRequest)
    }
    get("/download") {
        call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
            writeFully(ByteArray(16 * 1024) { 's'.code.toByte() })
            flush()
            delay(50)
            writeFully(ByteArray(16 * 1024 + 3) { 't'.code.toByte() })
        }
    }
    get("/wait") {
        delay(20)
        call.respondText("ready")
    }
}
