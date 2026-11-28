package codes.draeger.examples.extensions

import codes.draeger.ktserve.opentelemetry.openTelemetry
import io.opentelemetry.api.OpenTelemetry

fun main(args: Array<String>) = runServer(args) {
    install(openTelemetry(OpenTelemetry.noop()))
}
