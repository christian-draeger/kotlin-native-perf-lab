package codes.draeger.ktserve

import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer

@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class KtServeRoutingDsl

/** An encoder and its wire schema for an explicit typed response format. */
class ResponseFormat<T>(
    val contentType: String,
    val descriptor: SerialDescriptor,
    internal val encode: (T) -> ByteArray,
) {
    init {
        requireContentType(contentType)
    }
}

/** Override a typed route's response with JSON, including for scalar values. */
inline fun <reified T> json(): ResponseFormat<T> = jsonFormat(serializer<T>())

@PublishedApi
internal fun <T> jsonFormat(serializer: KSerializer<T>): ResponseFormat<T> =
    ResponseFormat("application/json", serializer.descriptor) { value ->
        Json.encodeToString(serializer, value).encodeToByteArray()
    }

/** A JSON request body with an optional validator that overrides group and listener defaults. */
class JsonBody<T> @PublishedApi internal constructor(
    internal val serializer: KSerializer<T>,
    internal val validation: BodyValidation<T>?,
)

inline fun <reified T> jsonBody(validate: BodyValidation<T>? = null): JsonBody<T> =
    JsonBody(serializer<T>(), validate)

@KtServeRoutingDsl
class ApiRoutes internal constructor(
    private val routes: Routes,
    private val prefix: String,
    private val parent: ApiRoutes? = null,
) {
    private var extensions: MutableMap<String, ServerExtension>? = null
    private var validators: List<BodyValidationResolver> = emptyList()

    init {
        routes.prepareTypedRoute {
            extensions?.let { installed ->
                val scope = ExtensionScope()
                installed.values.forEach { it.install(scope) }
                validators = scope.bodyValidators(scoped = true)
                extensions = null
            }
        }
    }

    /** Install a typed body validation extension on this group and its descendants. */
    fun install(extension: ServerExtension) {
        routes.checkConfiguring()
        val id = extension.id
        require(id.isNotBlank()) { "An extension ID must not be blank" }
        val installed = extensions ?: linkedMapOf<String, ServerExtension>().also { extensions = it }
        check(id !in installed) { "Extension '$id' is already installed in this API group" }
        installed[id] = extension
    }

    fun api(prefix: String = "", configure: ApiRoutes.() -> Unit) {
        routes.checkConfiguring()
        ApiRoutes(routes, this.prefix + apiPrefix(prefix), this).apply(configure)
    }

    /** Encode an ordinary Kotlin return value or pass through a [Response]. */
    inline fun <reified T> get(
        path: String,
        returns: ResponseFormat<T>? = null,
        noinline handler: (Request) -> T,
    ) = registerGet(path, typedOutput(returns, status = 200), handler)

    /** Encode an ordinary Kotlin return value from a suspending handler. */
    inline fun <reified T> getSuspending(
        path: String,
        returns: ResponseFormat<T>? = null,
        noinline handler: suspend (Request) -> T,
    ) = registerSuspendingGet(path, typedOutput(returns, status = 200), handler)

    /** Decode and validate a JSON body before calling the handler. */
    inline fun <reified In, reified Out> post(
        path: String,
        status: Int = 200,
        body: JsonBody<In>? = null,
        returns: ResponseFormat<Out>? = null,
        noinline handler: suspend Request.(In) -> Out,
    ) = route("POST", path, status, body, returns, handler)

    /** Decode a JSON body and encode the return value for an ordinary HTTP method. */
    inline fun <reified In, reified Out> route(
        method: String,
        path: String,
        status: Int = 200,
        body: JsonBody<In>? = null,
        returns: ResponseFormat<Out>? = null,
        noinline handler: suspend Request.(In) -> Out,
    ) = registerBody(method, path, typeOf<In>(), body ?: jsonBody(), typedOutput(returns, status), handler)

    @PublishedApi
    internal fun <T> registerGet(path: String, output: TypedOutput<T>, handler: (Request) -> T) {
        registerGet(path, output.metadata, GetHandler.Immediate { request -> output.respond(handler(request)) })
    }

    @PublishedApi
    internal fun <T> registerSuspendingGet(path: String, output: TypedOutput<T>, handler: suspend (Request) -> T) {
        registerGet(path, output.metadata, GetHandler.Suspending { request -> output.respond(handler(request)) })
    }

    private fun registerGet(path: String, metadata: TypedResponseMetadata?, handler: GetHandler) {
        routes.checkConfiguring()
        routes.typedGet(routePath(path), handler)
        routes.recordTypedRoute(handler, TypedRouteMetadata(body = null, response = metadata))
    }

    @PublishedApi
    internal fun <In, Out> registerBody(
        method: String,
        path: String,
        type: KType,
        body: JsonBody<In>,
        output: TypedOutput<Out>,
        handler: suspend Request.(In) -> Out,
    ) {
        routes.checkConfiguring()
        val serializer = body.serializer
        var validator = body.validation
        val routeHandler: RequestHandler = handler@{ request, content ->
            if (!request.hasJsonBody()) return@handler unsupportedJsonMediaType
            val input = try {
                Json.decodeFromString(serializer, content.readUtf8())
            } catch (_: SerializationException) {
                return@handler malformedJsonBody
            } catch (_: CharacterCodingException) {
                return@handler malformedJsonBody
            }
            val violations = validator?.validate?.invoke(input)
            if (!violations.isNullOrEmpty()) return@handler problem(
                status = 422,
                title = "Unprocessable Content",
                detail = "The request body failed validation.",
                errors = violations,
            )
            output.respond(request.handler(input))
        }
        routes.route(method, routePath(path), routeHandler)
        routes.prepareTypedRoute { inherited ->
            validator = body.validation ?: resolveValidator(type, serializer.descriptor)
                ?: inherited.findValidator(type, serializer.descriptor)
            routes.recordTypedRoute(
                routeHandler,
                TypedRouteMetadata(
                    body = TypedBodyMetadata(type, serializer.descriptor, "application/json", validator?.let {
                        BodyValidationMetadata(it.schema)
                    }),
                    response = output.metadata,
                ),
            )
        }
    }

    private fun resolveValidator(
        type: KType,
        descriptor: SerialDescriptor,
    ): BodyValidation<Any?>? = validators.findValidator(type, descriptor)
        ?: parent?.resolveValidator(type, descriptor)

    private fun routePath(path: String): String {
        require(path.startsWith('/')) { "Expected a route path beginning with '/'" }
        return prefix + path
    }
}

internal fun apiPrefix(prefix: String): String {
    require((prefix.isEmpty() || prefix.startsWith('/')) && prefix.none { it in "{}?#" }) {
        "Expected a literal API path prefix"
    }
    return prefix.trimEnd('/')
}

private fun List<BodyValidationResolver>.findValidator(type: KType, descriptor: SerialDescriptor): BodyValidation<Any?>? =
    firstNotNullOfOrNull { it(type, descriptor) }

@PublishedApi
internal class TypedOutput<T>(val metadata: TypedResponseMetadata?, val respond: (T) -> Response)

@PublishedApi
internal inline fun <reified T> typedOutput(format: ResponseFormat<T>?, status: Int): TypedOutput<T> {
    if (format == null && T::class == Response::class && !typeOf<T>().isMarkedNullable) {
        require(status == 200) { "Set the status on the Response returned from the handler" }
        return TypedOutput(metadata = null) { it as Response }
    }
    return typedEncodedOutput(format ?: defaultResponseFormat<T>(), status)
}

@PublishedApi
internal fun <T> typedEncodedOutput(output: ResponseFormat<T>, status: Int): TypedOutput<T> {
    require(status in 200..599 && status != 204 && status != 205 && status != 304) {
        "Expected an HTTP response status that allows a body"
    }
    val contentType = output.contentType.substringBefore(';').trim()
    return TypedOutput(TypedResponseMetadata(status, contentType, output.descriptor)) {
        Response(status, output.contentType, output.encode(it))
    }
}

@PublishedApi
internal inline fun <reified T> defaultResponseFormat(): ResponseFormat<T> {
    val serializer = serializer<T>()
    val scalar = when (T::class) {
        String::class, Char::class, Boolean::class,
        Byte::class, Short::class, Int::class, Long::class, Float::class, Double::class,
        UByte::class, UShort::class, UInt::class, ULong::class -> true
        else -> false
    }
    return if (scalar && !typeOf<T>().isMarkedNullable) {
        ResponseFormat("text/plain; charset=UTF-8", serializer.descriptor) { it.toString().encodeToByteArray() }
    } else {
        jsonFormat(serializer)
    }
}

private fun Request.hasJsonBody(): Boolean = headerValues("Content-Type").singleOrNull()
    ?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true

private val unsupportedJsonMediaType = problem(415, "Unsupported Media Type", "Content-Type must be application/json.")
private val malformedJsonBody = problem(400, "Bad Request", "The request body must match the declared JSON type.")

private fun problem(status: Int, title: String, detail: String, errors: List<BodyViolation>? = null): Response {
    val payload = buildJsonObject {
        put("type", "about:blank")
        put("title", title)
        put("status", status)
        put("detail", detail)
        if (errors != null) putJsonArray("errors") {
            for (error in errors) add(buildJsonObject {
                put("detail", error.detail)
                error.pointer?.let { put("pointer", it) }
            })
        }
    }
    return response(Json.encodeToString(payload).encodeToByteArray(), status, "application/problem+json")
}
