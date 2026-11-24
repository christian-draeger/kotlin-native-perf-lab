package dev.perflab.nativeserve.ktor

import dev.perflab.nativeserve.ktor.example.exampleModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorEngineTest {
    @Test
    fun theSameKtorModuleRunsOnNativeServeCioAndNetty() {
        checkApplication(NativeServe)
        checkApplication(CIO)
        checkApplication(Netty)
    }

    private fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> checkApplication(
        factory: ApplicationEngineFactory<E, C>,
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(factory, port = port, host = "127.0.0.1") { exampleModule() }.start()
        try {
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()

            fun request(path: String, body: ByteArray? = null): HttpResponse<ByteArray> {
                val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
                if (body != null) builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            }

            val health = request("/health")
            assertEquals(200, health.statusCode())
            assertEquals("ok", health.body().decodeToString())
            assertEquals("same-ktor-application", health.headers().firstValue("X-Example").orElse(null))
            assertEquals(
                """{"id":42,"name":"benchmark-user","active":true}""",
                request("/json").body().decodeToString(),
            )
            assertEquals("24576", request("/upload", ByteArray(24 * 1024)).body().decodeToString())
            assertContentEquals(
                ByteArray(16 * 1024) { 's'.code.toByte() } + ByteArray(16 * 1024 + 3) { 't'.code.toByte() },
                request("/download").body(),
            )
            assertEquals(404, request("/missing").statusCode())
            assertEquals(500, request("/failure").statusCode())
        } finally {
            server.stop(500, 5_000)
        }
        assertFailsWith<java.net.ConnectException> { Socket("127.0.0.1", port).close() }
    }
}
