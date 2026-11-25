@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package codes.draeger.ktserve.ktor

import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.ServerLimits
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.util.pipeline.execute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean

/** Ktor engine backed by the Linux or JVM ktServe HTTP/1.x transport. */
object KtServe : ApplicationEngineFactory<KtServeEngine, KtServeEngine.Configuration> {
    override fun configuration(configure: KtServeEngine.Configuration.() -> Unit) =
        KtServeEngine.Configuration().apply(configure)

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: KtServeEngine.Configuration,
        applicationProvider: () -> Application,
    ): KtServeEngine = KtServeEngine(environment, monitor, developmentMode, configuration, applicationProvider)
}

internal expect fun serveListener(
    port: Int,
    host: String,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
    onListening: () -> Unit,
    configure: Routes.() -> Unit,
)

class KtServeEngine internal constructor(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    configuration: Configuration,
    private val applicationProvider: () -> Application,
) : BaseApplicationEngine(environment, monitor, developmentMode) {
    class Configuration : BaseApplicationEngine.Configuration() {
        var limits: ServerLimits = ServerLimits()
    }

    private val connector = configuration.connectors.singleOrNull()
        ?: error("ktServe requires one HTTP connector")
    private val address = connector.host.let { if (it == "localhost") "127.0.0.1" else it }
    private val stopping = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()
    private val limits = configuration.limits

    init {
        require(connector.type == ConnectorType.HTTP && connector.port in 1..65535) {
            "ktServe requires a fixed-port IPv4 HTTP connector"
        }
    }

    private val owner = newSingleThreadContext("ktserve-ktor")
    private val server = CoroutineScope(applicationProvider().parentCoroutineContext + owner)
        .launch(start = CoroutineStart.LAZY) {
            serveListener(
                port = connector.port,
                host = address,
                limits = limits,
                stopRequested = { stopping.load() || !isActive },
                onListening = {
                    resolvedConnectorsDeferred.complete(listOf(connector))
                    ready.complete(Unit)
                },
            ) {
                application { request, body ->
                    executeCall(applicationProvider(), request, body, address, connector.port) { call ->
                        pipeline.execute(call)
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null) {
                    ready.completeExceptionally(cause)
                    resolvedConnectorsDeferred.completeExceptionally(cause)
                }
                // Native dispatchers join their worker on close, so release it from another thread.
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        owner.close()
                        terminated.complete(Unit)
                    } catch (failure: Exception) {
                        terminated.completeExceptionally(failure)
                    }
                }
            }
        }

    override fun start(wait: Boolean): ApplicationEngine = runBlocking { startSuspend(wait) }

    override suspend fun startSuspend(wait: Boolean): ApplicationEngine {
        server.start()
        ready.await()
        monitor.raise(ServerReady, environment)
        if (wait) terminated.await()
        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long): Unit = runBlocking {
        stopSuspend(gracePeriodMillis, timeoutMillis)
    }

    override suspend fun stopSuspend(gracePeriodMillis: Long, timeoutMillis: Long) {
        if (stopping.compareAndSet(expectedValue = false, newValue = true)) {
            monitor.raise(ApplicationStopPreparing, environment)
        }
        if (!server.isActive) server.cancel()
        withTimeoutOrNull(timeoutMillis) { terminated.await() }
    }
}
