package codes.draeger.ktserve.spring

import codes.draeger.ktserve.ServerLimits
import codes.draeger.ktserve.webflux.serveWebFlux
import java.net.Inet4Address
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.server.GracefulShutdownCallback
import org.springframework.boot.web.server.GracefulShutdownResult
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.WebServerException
import org.springframework.boot.web.server.reactive.AbstractReactiveWebServerFactory
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.http.server.reactive.HttpHandler

@AutoConfiguration(beforeName = [
    "org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration",
    "org.springframework.boot.jetty.autoconfigure.reactive.JettyReactiveWebServerAutoConfiguration",
    "org.springframework.boot.tomcat.autoconfigure.reactive.TomcatReactiveWebServerAutoConfiguration",
])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "ktserve", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(KtServeProperties::class)
class KtServeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReactiveWebServerFactory::class)
    fun ktServeWebServerFactory(properties: KtServeProperties): KtServeWebServerFactory =
        KtServeWebServerFactory(properties.limits())
}

@ConfigurationProperties("ktserve")
data class KtServeProperties(
    val connections: Int = 1024,
    val requestHeaderBytes: Int = 16 * 1024,
    val requestBodyBytes: Int = 64 * 1024,
    val responseHeaderBytes: Int = 16 * 1024,
    val responseBodyBytes: Int = 64 * 1024,
    val idleTimeout: Duration = Duration.ofSeconds(30),
    val requestHeaderTimeout: Duration = Duration.ofSeconds(5),
    val requestBodyTimeout: Duration = Duration.ofSeconds(30),
    val handlerTimeout: Duration = Duration.ofSeconds(30),
    val drainTimeout: Duration = Duration.ofSeconds(2),
) {
    internal fun limits() = ServerLimits(
        connections = connections,
        headerBytes = requestHeaderBytes,
        requestBodyBytes = requestBodyBytes,
        responseHeaderBytes = responseHeaderBytes,
        responseBodyBytes = responseBodyBytes,
        idleMillis = idleTimeout.toMillis(),
        requestHeadMillis = requestHeaderTimeout.toMillis(),
        requestBodyMillis = requestBodyTimeout.toMillis(),
        handlerMillis = handlerTimeout.toMillis(),
        drainMillis = drainTimeout.toMillis(),
    )
}

class KtServeWebServerFactory(private val limits: ServerLimits = ServerLimits()) : AbstractReactiveWebServerFactory() {
    override fun getWebServer(httpHandler: HttpHandler): WebServer {
        check(port in 1..65535) { "ktServe requires server.port between 1 and 65535" }
        check(address == null || address is Inet4Address) { "ktServe requires an IPv4 server.address" }
        check(ssl?.isEnabled != true && http2?.isEnabled != true) {
            "The KtServe WebFlux listener requires plain HTTP/1.x"
        }
        return KtServeWebServer(httpHandler, address?.hostAddress ?: "0.0.0.0", port, limits)
    }
}

private class KtServeWebServer(
    private val handler: HttpHandler,
    private val address: String,
    private val configuredPort: Int,
    private val limits: ServerLimits,
) : WebServer {
    @Volatile
    private var active: Running? = null

    @Synchronized
    override fun start() {
        if (active != null) return
        val running = Running()
        active = running
        thread(name = "ktserve-webflux-$configuredPort", isDaemon = false) {
            try {
                serveWebFlux(handler, configuredPort, address, limits, running.stopping::get) {
                    running.ready.complete(Unit)
                }
                running.finished.complete(Unit)
            } catch (cause: Throwable) {
                running.ready.completeExceptionally(cause)
                running.finished.completeExceptionally(cause)
            }
        }
        try {
            running.ready.get(10, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            running.stopping.set(true)
            active = null
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw WebServerException("Could not start KtServe on $address:$configuredPort", failure.unwrap())
        }
    }

    override fun getPort(): Int {
        val running = active ?: return -1
        return if (running.ready.isDone && !running.ready.isCompletedExceptionally && !running.finished.isDone) {
            configuredPort
        } else -1
    }

    override fun shutDownGracefully(callback: GracefulShutdownCallback) {
        val running = active
        if (running == null) {
            callback.shutdownComplete(GracefulShutdownResult.IDLE)
            return
        }
        running.stopping.set(true)
        running.finished.whenComplete { _, failure ->
            val result = if (failure == null) GracefulShutdownResult.IDLE else GracefulShutdownResult.REQUESTS_ACTIVE
            callback.shutdownComplete(result)
        }
    }

    @Synchronized
    override fun stop() {
        val running = active ?: return
        running.stopping.set(true)
        try {
            running.finished.get(limits.drainMillis + 2500, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw WebServerException("Could not stop KtServe on $address:$configuredPort", failure.unwrap())
        } finally {
            active = null
        }
    }

    private class Running {
        val stopping = AtomicBoolean(false)
        val ready = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
    }
}

private fun Exception.unwrap(): Throwable = if (this is ExecutionException) cause ?: this else this
