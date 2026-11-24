package codes.draeger.benchmark.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException

internal enum class UpstreamResult(val status: Int, val message: String) {
    Ready(200, "origin-ready"),
    UnknownScenario(404, "unknown-scenario"),
    Unavailable(502, "origin-unavailable"),
    Deadline(504, "origin-deadline"),
}

internal class UpstreamClient(port: Int) {
    private val origin = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) {
        followRedirects = false
    }

    suspend fun request(scenario: String): UpstreamResult {
        when (scenario) {
            "fast", "slow", "deadline", "disconnect" -> Unit
            else -> return UpstreamResult.UnknownScenario
        }
        return try {
            withTimeoutOrNull(150) {
                val reply = client.get("$origin/$scenario") {
                    header(HttpHeaders.Connection, "close")
                }
                if (reply.status == HttpStatusCode.OK && reply.bodyAsText() == "ready") UpstreamResult.Ready
                else UpstreamResult.Unavailable
            } ?: UpstreamResult.Deadline
        } catch (_: IOException) {
            UpstreamResult.Unavailable
        }
    }

    fun close() = client.close()
}
