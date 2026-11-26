package codes.draeger.ktserve.example

import codes.draeger.benchmark.upstream.UpstreamClient
import codes.draeger.benchmark.upstream.UpstreamResult
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String) = getenv(name)?.toKString()

fun runUpstreamProbe() {
    val port = env("PORT")?.toInt() ?: 8080
    val originPort = requireNotNull(env("BENCHMARK_UPSTREAM_PORT")?.toInt())
    val mode = env("UPSTREAM_CONNECTION_MODE") ?: "fresh"
    require(mode == "fresh" || mode == "keep-alive") { "Unsupported upstream connection mode: $mode" }
    val reuse = mode == "keep-alive"
    when (val engine = env("UPSTREAM_CLIENT") ?: if (reuse) "socket" else "cio") {
        "cio" -> {
            require(!reuse) { "The Kotlin/Native CIO engine does not support connection reuse" }
            val client = UpstreamClient(originPort)
            try {
                runProbe(port, client::request)
            } finally {
                client.close()
            }
        }
        "socket" -> {
            val client = SocketUpstreamClient(originPort, reuse)
            try {
                runProbe(port, client::request)
            } finally {
                client.close()
            }
        }
        else -> error("Unsupported upstream client: $engine")
    }
}

private fun runProbe(port: Int, fetch: suspend (String) -> UpstreamResult) {
    if (env("UPSTREAM_CLIENT_ONLY") == "1") {
        runBlocking { runClientOnly(fetch) }
        return
    }
    serve(port = port, host = "127.0.0.1", workers = 1) {
        parityRoutes()
        getSuspending("/upstream/{scenario}") { request ->
            val result = fetch(requireNotNull(request.pathParameter("scenario")))
            text(result.message, result.status)
        }
    }
}

private suspend fun runClientOnly(fetch: suspend (String) -> UpstreamResult) {
    val count = requireNotNull(env("UPSTREAM_REQUESTS")?.toIntOrNull())
    require(count in 1..1_000_000) { "UPSTREAM_REQUESTS must be between 1 and 1,000,000" }
    val scenario = env("UPSTREAM_SCENARIO") ?: "fast"
    val expected = when (scenario) {
        "fast", "slow" -> UpstreamResult.Ready
        "deadline" -> UpstreamResult.Deadline
        "disconnect" -> UpstreamResult.Unavailable
        else -> error("Unsupported client-only scenario: $scenario")
    }
    val elapsed = LongArray(count)
    var matched = 0
    repeat(count) { index ->
        val start = TimeSource.Monotonic.markNow()
        if (fetch(scenario) == expected) matched++
        elapsed[index] = start.elapsedNow().inWholeMicroseconds
    }
    elapsed.sort()
    fun percentile(percent: Int) = elapsed[(percent * count + 99) / 100 - 1]
    println(
        """{"requests":$count,"expected":$matched,"unexpected":${count - matched},"p50_us":${percentile(50)},"p95_us":${percentile(95)},"p99_us":${percentile(99)}}""",
    )
    check(matched == count) { "Unexpected client-only responses" }
}
