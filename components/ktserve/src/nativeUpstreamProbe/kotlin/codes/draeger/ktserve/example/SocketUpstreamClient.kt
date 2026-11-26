package codes.draeger.ktserve.example

import codes.draeger.benchmark.upstream.UpstreamResult
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readLineStrict
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val MAX_CONNECTIONS = 64
private const val READY_BODY_LENGTH = 5

// The probe only speaks to the checked-in loopback origin with its small, fixed-length response.
internal class SocketUpstreamClient(private val port: Int, private val reuse: Boolean) {
    private val selector = SelectorManager(Dispatchers.IO)
    private val permits = Semaphore(MAX_CONNECTIONS)
    private val idle = Channel<IdleConnection>(MAX_CONNECTIONS)

    suspend fun request(scenario: String): UpstreamResult {
        when (scenario) {
            "fast", "slow", "deadline", "disconnect" -> Unit
            else -> return UpstreamResult.UnknownScenario
        }
        return try {
            withTimeoutOrNull(150) { permits.withPermit { exchange(scenario) } } ?: UpstreamResult.Deadline
        } catch (_: IOException) {
            UpstreamResult.Unavailable
        }
    }

    private suspend fun exchange(scenario: String): UpstreamResult {
        val connection = takeIdle() ?: openConnection()
        var returned = false
        try {
            val connectionHeader = if (reuse) "" else "Connection: close\r\n"
            connection.output.writeStringUtf8("GET /$scenario HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$connectionHeader\r\n")
            val reply = connection.readReply()
            if (reply == Reply.Unavailable) return UpstreamResult.Unavailable
            if (reuse && reply == Reply.Reusable) {
                returned = idle.trySend(IdleConnection(connection, TimeSource.Monotonic.markNow())).isSuccess
            }
            return UpstreamResult.Ready
        } finally {
            // An incomplete exchange still owns the socket. Close it before releasing its permit.
            if (!returned) connection.socket.close()
        }
    }

    private suspend fun openConnection(): OriginConnection {
        val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        return try {
            OriginConnection(socket)
        } catch (cause: Throwable) {
            socket.close()
            throw cause
        }
    }

    private fun takeIdle(): OriginConnection? {
        while (true) {
            val entry = idle.tryReceive().getOrNull() ?: return null
            if (entry.since.elapsedNow() < 1.seconds && !entry.connection.input.isClosedForRead) return entry.connection
            entry.connection.socket.close()
        }
    }

    fun close() {
        idle.close()
        while (true) {
            val entry = idle.tryReceive().getOrNull() ?: break
            entry.connection.socket.close()
        }
        selector.close()
    }

    private class OriginConnection(val socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)

        suspend fun readReply(): Reply {
            val status = input.readLineStrict(128) ?: return Reply.Unavailable
            if (!status.startsWith("HTTP/1.1 200 ")) return Reply.Unavailable
            var contentLength: Int? = null
            var reusable = true
            repeat(16) {
                val line = input.readLineStrict(128) ?: return Reply.Unavailable
                if (line.isEmpty()) {
                    if (contentLength != READY_BODY_LENGTH) return Reply.Unavailable
                    val body = ByteArray(READY_BODY_LENGTH)
                    input.readFully(body)
                    if (body.decodeToString() != "ready") return Reply.Unavailable
                    return if (reusable) Reply.Reusable else Reply.Closing
                }
                val separator = line.indexOf(':')
                if (separator < 1) return Reply.Unavailable
                val name = line.substring(0, separator)
                val value = line.substring(separator + 1).trim()
                when {
                    name.equals("Content-Length", ignoreCase = true) -> {
                        if (contentLength != null) return Reply.Unavailable
                        contentLength = value.toIntOrNull() ?: return Reply.Unavailable
                    }
                    name.equals("Transfer-Encoding", ignoreCase = true) -> return Reply.Unavailable
                    name.equals("Connection", ignoreCase = true) -> {
                        if (value.split(',').any { it.trim().equals("close", ignoreCase = true) }) reusable = false
                    }
                }
            }
            return Reply.Unavailable
        }
    }

    private enum class Reply { Reusable, Closing, Unavailable }
    private data class IdleConnection(val connection: OriginConnection, val since: TimeMark)
}
