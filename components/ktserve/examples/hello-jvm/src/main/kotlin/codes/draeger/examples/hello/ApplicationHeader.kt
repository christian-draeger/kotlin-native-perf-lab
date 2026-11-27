package codes.draeger.examples.hello

import codes.draeger.ktserve.extension

internal val applicationHeader = extension("example.application-header") {
    onResponseHeaders { _, headers ->
        headers.set("X-App", "hello")
    }
}
