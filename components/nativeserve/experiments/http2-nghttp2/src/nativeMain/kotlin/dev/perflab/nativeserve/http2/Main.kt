@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.http2

import dev.perflab.nativeserve.http2.bridge.probe_version
import kotlinx.cinterop.toKString

fun main() {
    val serverEvents = RecordingEvents()
    val clientEvents = RecordingEvents()
    Http2Session(server = true, serverEvents).use { server ->
        Http2Session(server = false, clientEvents).use { client ->
            val link = FragmentedLink(client, server)
            val large = client.get("/large")
            val small = client.get("/health")
            link.settle()
            check(serverEvents.ended == setOf(large, small))
            check(serverEvents.headers.getValue(large)[":method"] == "GET")
            check(serverEvents.headers.getValue(small)[":path"] == "/health")

            val largeSize = 96 * 1024
            val smallBody = StringBuilder()
            clientEvents.checkData = { stream, bytes ->
                when (stream) {
                    small -> smallBody.append(bytes.decodeToString())
                    large -> check(bytes.all { it == 'x'.code.toByte() })
                    else -> error("Unexpected response data on stream $stream")
                }
            }
            server.respond(small, 200, "ok".encodeToByteArray())
            server.respond(large, 200, ByteArray(largeSize) { 'x'.code.toByte() })
            link.settle()
            val stalledBytes = clientEvents.counts.getValue(large)
            check(stalledBytes in 1 until largeSize && large !in clientEvents.ended)
            check(smallBody.toString() == "ok" && small in clientEvents.ended)
            check(clientEvents.headers.getValue(small)[":status"] == "200")
            client.addCredit(large, stalledBytes)
            link.settle()
            check(clientEvents.counts.getValue(large) == largeSize && large in clientEvents.ended)

            val cancelled = client.get("/cancel")
            link.settle()
            check(cancelled in serverEvents.ended)
            client.cancel(cancelled)
            link.settle()
            check(serverEvents.closed[cancelled] == 8U)
            println("nghttp2 ${probe_version()?.toKString()} exchanged ${link.bytes} wire bytes in ${link.fragments} input fragments")
            println("Two streams progressed independently. Flow control paused at $stalledBytes bytes. Cancellation delivered RST_STREAM code 8.")
        }
    }
}

private class RecordingEvents : SessionEvents {
    val headers = mutableMapOf<Int, MutableMap<String, String>>()
    val counts = mutableMapOf<Int, Int>()
    val ended = mutableSetOf<Int>()
    val closed = mutableMapOf<Int, UInt>()
    var checkData: (Int, ByteArray) -> Unit = { _, _ -> }

    override fun header(stream: Int, name: String, value: String): Boolean {
        headers.getOrPut(stream) { mutableMapOf() }[name] = value
        return true
    }

    override fun data(stream: Int, bytes: ByteArray) {
        checkData(stream, bytes)
        counts[stream] = (counts[stream] ?: 0) + bytes.size
    }

    override fun end(stream: Int) {
        check(ended.add(stream))
    }

    override fun closed(stream: Int, code: UInt) {
        check(closed.put(stream, code) == null)
    }
}

private class FragmentedLink(private val client: Http2Session, private val server: Http2Session) {
    private val buffer = ByteArray(1_024)
    var bytes = 0
        private set
    var fragments = 0
        private set

    fun settle() {
        repeat(100) {
            val sent = transfer(client, server) + transfer(server, client)
            if (sent == 0) return
        }
        error("HTTP/2 sessions did not settle")
    }

    private fun transfer(sender: Http2Session, receiver: Http2Session): Int {
        var delivered = 0
        while (true) {
            val count = sender.output(buffer)
            if (count == 0) return delivered
            for (start in 0 until count step 67) {
                receiver.receive(buffer, start, minOf(67, count - start))
                fragments++
            }
            bytes += count
            delivered += count
        }
    }
}
