package codes.draeger.ktserve.spring

import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

class KtServeStarterTest {
    @Test
    fun optInSelectsKtServeAndBindsStandardServerProperties() {
        val port = ServerSocket(0).use { it.localPort }
        start(port, enabled = true).use { context ->
            assertIs<KtServeWebServerFactory>(context.getBean(ReactiveWebServerFactory::class.java))
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val health = client.send(request(port, "/health").build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, health.statusCode())
            assertTrue(health.body().contains("\"status\":\"ok\""))
            val oversized = client.send(
                request(port, "/echo").header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("sixxxx")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(413, oversized.statusCode())
        }
    }

    @Test
    fun disabledStarterPreservesBootServerSelection() {
        val port = ServerSocket(0).use { it.localPort }
        start(port, enabled = false).use { context ->
            assertIs<NettyReactiveWebServerFactory>(context.getBean(ReactiveWebServerFactory::class.java))
        }
    }

    @Test
    fun bootShutdownStopsAcceptingAndDrainsAnActiveResponse() {
        val port = ServerSocket(0).use { it.localPort }
        val context = start(port, enabled = true)
        val controller = context.getBean(FixtureController::class.java)
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val pending = client.sendAsync(request(port, "/slow").build(), HttpResponse.BodyHandlers.ofString())
        try {
            controller.entered.get(5, TimeUnit.SECONDS)
            val closed = CompletableFuture.runAsync { context.close() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (true) {
                try {
                    Socket("127.0.0.1", port).close()
                } catch (_: ConnectException) {
                    break
                }
                check(System.nanoTime() < deadline) { "KtServe kept accepting sockets while draining" }
                Thread.sleep(10)
            }
            controller.body.complete("finished")
            assertEquals("finished", pending.get(5, TimeUnit.SECONDS).body())
            closed.get(5, TimeUnit.SECONDS)
        } finally {
            controller.body.complete("finished")
            context.close()
        }
    }

    private fun start(port: Int, enabled: Boolean) = SpringApplicationBuilder(FixtureApplication::class.java)
        .web(WebApplicationType.REACTIVE)
        .properties(
            "server.port=$port",
            "server.address=127.0.0.1",
            "server.shutdown=graceful",
            "ktserve.enabled=$enabled",
            "ktserve.request-body-bytes=4",
        )
        .run()

    private fun request(port: Int, path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
}

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(FixtureController::class)
class FixtureApplication

@RestController
class FixtureController {
    val entered = CompletableFuture<Unit>()
    val body = CompletableFuture<String>()

    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    @PostMapping("/echo")
    fun echo(@RequestBody payload: String) = payload

    @GetMapping("/slow")
    fun slow(): Mono<String> {
        entered.complete(Unit)
        return Mono.fromFuture(body)
    }
}
