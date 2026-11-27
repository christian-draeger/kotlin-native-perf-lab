package codes.draeger.ktserve.opentelemetry

import codes.draeger.ktserve.ExchangeOutcome
import codes.draeger.ktserve.Request
import codes.draeger.ktserve.RequestAttribute
import codes.draeger.ktserve.ServerExtension
import codes.draeger.ktserve.extension
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

private const val INSTRUMENTATION_SCOPE = "codes.draeger.ktserve"
private const val OTHER_METHOD = "_OTHER"
private val knownMethods = setOf("CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "QUERY", "TRACE")
private val serverSpan = RequestAttribute<Span>("codes.draeger.ktserve.opentelemetry.serverSpan")

/**
 * Trace request exchanges with the supplied OpenTelemetry instance and its configured propagator.
 * The application owns the SDK, sampling, batching and export.
 */
fun openTelemetry(telemetry: OpenTelemetry): ServerExtension {
    val tracer = telemetry.getTracer(INSTRUMENTATION_SCOPE)
    val propagator = telemetry.propagators.textMapPropagator

    return extension("codes.draeger.ktserve.opentelemetry") {
        onRequestStart { exchange ->
            val request = exchange.request
            val parent = propagator.extract(Context.root(), request, RequestHeaders)
            val method = request.method.instrumentedMethod()
            val span = tracer.spanBuilder(method.spanMethod())
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .setAttribute("http.request.method", method)
                .setAttribute("network.protocol.version", request.version.removePrefix("HTTP/"))
                .startSpan()
            if (span.isRecording) exchange[serverSpan] = span
        }

        onComplete { exchange, completion ->
            val span = exchange[serverSpan] ?: return@onComplete
            try {
                exchange.route?.template?.let { route ->
                    span.setAttribute("http.route", route)
                    span.updateName("${exchange.request.method.instrumentedMethod().spanMethod()} $route")
                }
                val status = completion.status
                if (status != null) span.setAttribute("http.response.status_code", status.toLong())
                val error = when {
                    completion.outcome != ExchangeOutcome.COMPLETED -> completion.outcome.name.lowercase()
                    status != null && status in 500..599 -> status.toString()
                    else -> null
                }
                if (error != null) {
                    span.setAttribute("error.type", error)
                    span.setStatus(StatusCode.ERROR)
                }
            } finally {
                span.end()
            }
        }
    }
}

private fun String.instrumentedMethod(): String = if (this in knownMethods) this else OTHER_METHOD

private fun String.spanMethod(): String = if (this == OTHER_METHOD) "HTTP" else this

private object RequestHeaders : TextMapGetter<Request> {
    override fun get(carrier: Request?, key: String): String? {
        val request = carrier ?: return null
        return when {
            key.equals("traceparent", ignoreCase = true) -> request.traceParent()
            key.equals("tracestate", ignoreCase = true) -> {
                val values = request.headerValues(key)
                when (values.size) {
                    0 -> null
                    1 -> values[0]
                    else -> values.joinToString(",")
                }
            }
            else -> request.header(key)
        }
    }

    override fun getAll(carrier: Request?, key: String): Iterator<String> {
        val request = carrier ?: return emptyList<String>().iterator()
        if (key.equals("traceparent", ignoreCase = true)) return listOfNotNull(request.traceParent()).iterator()
        return request.headerValues(key).iterator()
    }
}

private fun Request.traceParent(): String? = headerValues("traceparent").singleOrNull()?.takeIf { ',' !in it }
