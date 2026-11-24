package codes.draeger.ktserve.example

import codes.draeger.ktserve.RequestBodySink
import codes.draeger.ktserve.Response
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.badRequest
import codes.draeger.ktserve.streamSuspending
import codes.draeger.ktserve.text
import kotlinx.coroutines.delay

internal fun Routes.applicationMixRoutes() {
    postStreaming("/upload") {
        object : RequestBodySink {
            private var total = 0
            private var valid = true

            override fun accept(buffer: ByteArray, length: Int) {
                total += length
                valid = valid && (0 until length).all { buffer[it] == 'x'.code.toByte() }
            }

            override fun complete(): Response = if (valid) text(total.toString()) else badRequest()
        }
    }
    get("/download") {
        streamSuspending(contentType = "application/octet-stream") {
            write(ByteArray(16 * 1024) { 's'.code.toByte() })
            delay(50)
            write(ByteArray(16 * 1024 + 3) { 't'.code.toByte() })
        }
    }
    getSuspending("/wait") {
        delay(20)
        text("ready")
    }
}
