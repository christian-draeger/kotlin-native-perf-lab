package codes.draeger.examples.extensions

import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.accesslog.accessLog
import codes.draeger.ktserve.bearer.bearer
import codes.draeger.ktserve.cors.cors
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.openapi.openApi
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.validation.validation

internal class Benchmark private constructor(val host: String, val port: Int, val mode: Mode) {
    enum class Mode(val argument: String) {
        NONE("none"), NOOP("noop"), CORS("cors"), BEARER("bearer"), VALIDATION("validation"),
        OPENAPI("openapi"), ACCESS_LOG("access-log"), ALL("all"),
        OTEL_UNSAMPLED("otel-unsampled"), OTEL_SAMPLED("otel-sampled");

        val samplingRatio: Double? get() = when (this) {
            OTEL_UNSAMPLED -> 0.0
            OTEL_SAMPLED -> 0.1
            else -> null
        }
    }

    fun run(stopRequested: () -> Boolean = { false }, tracing: (Routes.() -> Unit)? = null) {
        require(mode.samplingRatio == null || tracing != null) { "OpenTelemetry benchmark modes require the JVM" }
        serve(port = port, host = host, stopRequested = stopRequested) {
            if (mode == Mode.NOOP) {
                install(extension("benchmark.noop") { onRequest { RequestDecision.Continue } })
            }
            if (mode.includes(Mode.CORS)) install(cors { allowOrigins(BROWSER_ORIGIN) })
            if (mode.includes(Mode.BEARER)) install(bearer())
            if (mode.includes(Mode.VALIDATION)) install(validation())
            if (mode.includes(Mode.OPENAPI)) install(openApi(title = "Benchmark", version = "1.0") {
                serveAt("/openapi.json")
            })
            if (mode.includes(Mode.ACCESS_LOG)) install(accessLog())
            tracing?.invoke(this)
            api("/api") {
                post("/tasks/preview") { input: TaskDraft -> input }
            }
        }
    }

    private fun Mode.includes(extension: Mode) = this == extension || this == Mode.ALL

    companion object {
        fun parse(args: Array<String>): Benchmark? {
            if (args.firstOrNull() != "--benchmark") return null
            require(args.size == 4) { "Usage: --benchmark HOST PORT MODE" }
            val mode = Mode.entries.firstOrNull { it.argument == args[3] }
                ?: error("Unknown benchmark mode '${args[3]}'")
            return Benchmark(args[1], args[2].toInt(), mode)
        }
    }
}
