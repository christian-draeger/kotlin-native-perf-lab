package dev.perflab.benchmark.spring

import dev.perflab.nativeserve.spring.NativeServeWebServerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.runApplication
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@SpringBootApplication(proxyBeanMethods = false)
class BenchmarkApplication

data class User(val id: Int, val name: String, val active: Boolean)
data class Catalog(val id: Int, val items: List<CatalogItem>)
data class CatalogItem(val sku: String, val name: String, val description: String, val stock: Stock)
data class Stock(val available: Boolean, val units: Int)

@RestController
class BenchmarkRoutes {
    @GetMapping("/health", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun health() = "ok"

    @GetMapping("/json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun json() = User(42, "benchmark-user", true)

    @GetMapping("/catalog/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun catalog(@PathVariable("id") id: Int) = Catalog(id, List(32) { index ->
        CatalogItem(
            "$id-$index", "Café item $index",
            "Roasted in München and packed for delivery.", Stock(index > 0, index * 3),
        )
    })

    @PostMapping(
        "/upload",
        consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE],
        produces = [MediaType.TEXT_PLAIN_VALUE],
    )
    fun upload(@RequestBody body: Flux<DataBuffer>): Mono<String> =
        body.map { buffer ->
            try {
                buffer.readableByteCount()
            } finally {
                DataBufferUtils.release(buffer)
            }
        }.reduce(0, Int::plus).map(Int::toString)

    @GetMapping("/download")
    fun download(response: ServerHttpResponse): Mono<Void> {
        response.headers.contentType = MediaType.APPLICATION_OCTET_STREAM
        val buffers = response.bufferFactory()
        return response.writeAndFlushWith(
            Flux.just(
                Mono.fromSupplier { buffers.wrap(ByteArray(16 * 1024) { 's'.code.toByte() }) },
                Mono.delay(Duration.ofMillis(50)).map {
                    buffers.wrap(ByteArray(16 * 1024 + 3) { 't'.code.toByte() })
                },
            ),
        )
    }
}

fun main(args: Array<String>) {
    val port = args[0].toInt()
    val expectedFactory = when (args[1]) {
        "reactor-netty" -> NettyReactiveWebServerFactory::class.java
        "nativeserve" -> NativeServeWebServerFactory::class.java
        else -> error("Expected reactor-netty or nativeserve")
    }
    val context = runApplication<BenchmarkApplication>(
        "--server.port=$port",
        "--server.address=127.0.0.1",
        "--spring.main.banner-mode=off",
        "--nativeserve.enabled=${args[1] == "nativeserve"}",
    )
    val factory = context.getBean(ReactiveWebServerFactory::class.java)
    if (!expectedFactory.isInstance(factory)) {
        context.close()
        error("Expected ${expectedFactory.name}, found ${factory.javaClass.name}")
    }
    println("perflab engine=${args[1]}")
}
