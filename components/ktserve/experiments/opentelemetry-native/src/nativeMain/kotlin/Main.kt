@file:OptIn(io.opentelemetry.kotlin.ExperimentalApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package probe

import codes.draeger.ktserve.Request
import codes.draeger.ktserve.RequestAttribute
import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import io.opentelemetry.kotlin.context.rootContext
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.propagation.TextMapGetter
import io.opentelemetry.kotlin.propagation.createW3CTraceContextPropagator
import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import io.opentelemetry.kotlin.tracing.export.batchSpanProcessor
import io.opentelemetry.kotlin.tracing.export.otlpHttpSpanExporter
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.coroutines.runBlocking

private object TraceParentGetter : TextMapGetter<Request> {
    override fun keys(carrier: Request) = listOf("traceparent", "tracestate")
    override fun get(carrier: Request, key: String) = carrier.header(key)
    override fun getAll(carrier: Request, key: String) = carrier.headerValues(key)
}

fun main(args: Array<String>) {
    val stopped = AtomicBoolean(false)
    val spanKey = RequestAttribute<Span>("span")
    val propagator = createW3CTraceContextPropagator()
    val sdk = createOpenTelemetry {
        serviceName = "ktserve-native-probe"
        tracerProvider {
            export {
                batchSpanProcessor(otlpHttpSpanExporter {
                    endpoint = args[1]
                    timeoutMs = 1_000
                }, maxQueueSize = 32, maxExportBatchSize = 16)
            }
        }
    }
    val tracer = sdk.tracerProvider.getTracer("ktserve-probe")
    try {
        serve(port = args[0].toInt(), workers = 1, stopRequested = stopped::load) {
            install(extension("example.native-trace") {
                onRequestStart { context ->
                    val parent = propagator.extract(rootContext(), context.request, TraceParentGetter)
                    context[spanKey] = tracer.startSpan(context.request.method, parent, SpanKind.SERVER)
                }
                onRoute { context, route ->
                    context[spanKey]?.setName("${route.method} ${route.template}")
                    context[spanKey]?.setStringAttribute("http.route", route.template)
                    RequestDecision.Continue
                }
                onComplete { context, completion ->
                    context[spanKey]?.let { span ->
                        completion.status?.let { span.setLongAttribute("http.response.status_code", it.toLong()) }
                        span.end()
                    }
                    stopped.store(true)
                }
            })
            get("/widgets/{id}") { text("native trace") }
        }
    } finally {
        runBlocking {
            println("flush=${sdk.forceFlush() == OperationResultCode.Success}")
            println("shutdown=${sdk.shutdown() == OperationResultCode.Success}")
        }
    }
}
