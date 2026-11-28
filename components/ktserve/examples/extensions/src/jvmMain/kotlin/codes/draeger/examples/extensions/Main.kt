package codes.draeger.examples.extensions

import codes.draeger.ktserve.opentelemetry.openTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private class DemoSpanExporter(private val logToConsole: Boolean) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        if (logToConsole) spans.forEach { println("span=${it.name} trace_id=${it.traceId}") }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private fun telemetry(ratio: Double, logToConsole: Boolean): OpenTelemetrySdk = OpenTelemetrySdk.builder()
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    .setTracerProvider(SdkTracerProvider.builder()
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(ratio)))
        .addSpanProcessor(BatchSpanProcessor.builder(DemoSpanExporter(logToConsole)).setMaxQueueSize(2048).build())
        .build())
    .build()

fun main(args: Array<String>) {
    val benchmark = Benchmark.parse(args)
    val stopping = AtomicBoolean()
    val stopped = CountDownLatch(1)
    val shutdown = Thread {
        stopping.set(true)
        stopped.await(8, TimeUnit.SECONDS)
    }
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        val samplingRatio = benchmark?.mode?.samplingRatio
        when {
            benchmark == null -> telemetry(ratio = 1.0, logToConsole = true).use { sdk ->
                runServer(args, stopping::get) { install(openTelemetry(sdk)) }
            }
            samplingRatio != null -> telemetry(ratio = samplingRatio, logToConsole = false).use { sdk ->
                benchmark.run(stopping::get) { install(openTelemetry(sdk)) }
            }
            else -> benchmark.run(stopping::get)
        }
    } finally {
        stopped.countDown()
    }
}
