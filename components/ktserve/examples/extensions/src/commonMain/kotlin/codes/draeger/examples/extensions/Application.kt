package codes.draeger.examples.extensions

import codes.draeger.ktserve.RequestAttribute
import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.Routes
import codes.draeger.ktserve.accesslog.accessLog
import codes.draeger.ktserve.bearer.BearerCredential
import codes.draeger.ktserve.bearer.bearer
import codes.draeger.ktserve.bearer.bearerCredential
import codes.draeger.ktserve.cors.cors
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.openapi.openApi
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import codes.draeger.ktserve.validation.*
import kotlinx.serialization.Serializable

private val region = RequestAttribute<String>("region")

private val requireRegion = extension("example.require-region") {
    onRequest { context ->
        val requestedRegion = context.request.header("X-Region")
        if (requestedRegion.isNullOrBlank()) {
            RequestDecision.Respond(text("Missing region", status = 400))
        } else {
            context[region] = requestedRegion
            RequestDecision.Continue
        }
    }
    onResponseHeaders { context, headers ->
        context[region]?.let { headers.set("X-Region", it) }
    }
}

@Serializable
@ValidateWith(CreateUser.Rules::class)
internal data class CreateUser(val name: String) {
    object Rules : KonformRules<CreateUser> by rules({ minLength(CreateUser::name, 2) })
}

internal fun runServer(args: Array<String>, platformExtensions: Routes.() -> Unit = {}) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    serve(port = port, host = "127.0.0.1") {
        install(cors {
            allowOrigins("https://app.example")
            allowHeaders("Authorization", "Content-Type", "X-Region")
            allowCredentials = true
        })
        install(accessLog())
        install(bearer())
        install(requireRegion)
        install(validation())
        install(openApi(title = "Example", version = "1") { serveAt("/openapi.json") })
        platformExtensions()

        get("/health") { request ->
            when (request.bearerCredential) {
                is BearerCredential.Present -> text("ok")
                else -> text("Bearer credential required", status = 401)
            }
        }
        api("/api") {
            post("/users", status = 201) { input: CreateUser -> input }
        }
    }
}
