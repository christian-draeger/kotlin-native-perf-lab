package codes.draeger.ktserve

import kotlin.reflect.KType
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject

/** Preview API for an extension configured once per standalone HTTP/1.x server. */
interface ServerExtension {
    val id: String
    fun install(scope: ExtensionScope)
}

fun extension(id: String, configure: ExtensionScope.() -> Unit): ServerExtension = object : ServerExtension {
    override val id: String = id
    override fun install(scope: ExtensionScope) = scope.configure()
}

@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class KtServeExtensionDsl

sealed interface RequestDecision {
    data object Continue : RequestDecision
    data class Respond(val response: Response) : RequestDecision
}

/** The method as registered, including GET when a HEAD request uses its handler. */
data class MatchedRoute(val method: String, val template: String)

/** A standalone route declared before the server starts. */
data class RegisteredRoute(
    val method: String,
    val template: String,
    val pathParameters: List<String>,
    val typed: TypedRouteMetadata? = null,
)

data class TypedRouteMetadata(val body: TypedBodyMetadata?, val response: TypedResponseMetadata?)

data class TypedBodyMetadata(
    val type: KType,
    val descriptor: SerialDescriptor,
    val contentType: String,
    val validation: BodyValidationMetadata?,
)

data class TypedResponseMetadata(val status: Int, val contentType: String, val descriptor: SerialDescriptor)

data class BodyValidationMetadata(val schema: JsonObject?)

/** A request-body error with an optional RFC 6901 pointer to the rejected JSON value. */
data class BodyViolation(val detail: String, val pointer: String? = null)

/** Return an empty list for a valid body. The optional schema describes constraints on its JSON representation. */
class BodyValidation<in T>(
    val schema: JsonObject? = null,
    val validate: suspend (T) -> List<BodyViolation>,
)

internal typealias BodyValidationResolver = (KType, SerialDescriptor) -> BodyValidation<Any?>?

/** A read-only snapshot of application routes with startup-only registration for extension endpoints. */
@KtServeExtensionDsl
class ExtensionStartup internal constructor(
    val routes: List<RegisteredRoute>,
    private var registerGet: ((String, (Request) -> Response) -> Unit)?,
) {
    /** Add a literal GET endpoint supplied by an extension. */
    fun get(path: String, handler: (Request) -> Response) {
        val register = checkNotNull(registerGet) { "Extension endpoints must be registered at startup" }
        register(path, handler)
    }

    internal fun finish() {
        registerGet = null
    }
}

/** A typed identity key. Use the same instance in the extension and the consuming handler. */
class RequestAttribute<T : Any>(val name: String)

class RequestContext internal constructor(request: Request) {
    var request: Request = request
        internal set
    var route: MatchedRoute? = null
        internal set
    private var attributes: MutableMap<RequestAttribute<*>, Any>? = null
    private var attributesWritable = true

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: RequestAttribute<T>): T? = attributes?.get(key) as T?

    /** Set an attribute before dispatching the handler. */
    operator fun <T : Any> set(key: RequestAttribute<T>, value: T) {
        check(attributesWritable) { "Request attributes can only be set before handler dispatch" }
        val values = attributes ?: mutableMapOf<RequestAttribute<*>, Any>().also { attributes = it }
        values[key] = value
    }

    internal fun freezeAttributes() {
        attributesWritable = false
    }
}

data class ExchangeCompletion(val status: Int?, val durationNanos: Long, val outcome: ExchangeOutcome)

/** An editor for application headers on the selected response, before wire encoding. */
class ResponseHeaders internal constructor(private val response: Response) {
    val status: Int get() = response.status
    private var edited: MutableList<HttpField>? = null

    fun values(name: String): List<String> =
        (edited ?: response.fields).mapNotNull { field -> field.value.takeIf { field.name.equals(name, ignoreCase = true) } }

    fun set(name: String, value: String) {
        val field = HttpField(name, value)
        field.requireApplicationField()
        val fields = mutableFields()
        fields.removeAll { it.name.equals(name, ignoreCase = true) }
        fields.add(field)
    }

    fun append(name: String, value: String) {
        val field = HttpField(name, value)
        field.requireApplicationField()
        mutableFields().add(field)
    }

    /** Remove all application field values with this name. */
    fun remove(name: String) {
        if ((edited ?: response.fields).any { it.name.equals(name, ignoreCase = true) }) {
            mutableFields().removeAll { it.name.equals(name, ignoreCase = true) }
        }
    }

    private fun mutableFields(): MutableList<HttpField> =
        edited ?: response.fields.toMutableList().also { edited = it }

    internal fun apply(): Response = edited?.let(response::replaceApplicationFields) ?: response
}

/**
 * Request callbacks run in installation order on the owning I/O loop. Return promptly and offload I/O.
 * A request callback may run concurrently on different loops. Treat context and header editors as borrowed.
 */
@KtServeExtensionDsl
class ExtensionScope internal constructor() {
    private val startup = mutableListOf<ExtensionStartup.() -> Unit>()
    private val bodyValidationResolvers = mutableListOf<BodyValidationResolver>()
    private val serverStarts = mutableListOf<() -> AutoCloseable>()
    private val observers = mutableListOf<ServerObserver>()
    private val requestStart = mutableListOf<(RequestContext) -> Unit>()
    private val request = mutableListOf<(RequestContext) -> RequestDecision>()
    private val route = mutableListOf<(RequestContext, MatchedRoute) -> RequestDecision>()
    private val headers = mutableListOf<(RequestContext, ResponseHeaders) -> Unit>()
    private val complete = mutableListOf<(RequestContext, ExchangeCompletion) -> Unit>()

    /** Run after the application registers its routes and before the listener starts. */
    fun onStartup(hook: ExtensionStartup.() -> Unit) {
        startup.add(hook)
    }

    /** Resolve validation once for each matching typed route. Return null to leave the body to other resolvers. */
    fun resolveBodyValidation(resolver: (KType, SerialDescriptor) -> BodyValidation<Any?>?) {
        bodyValidationResolvers.add(resolver)
    }

    internal fun bodyValidators(scoped: Boolean = false): List<BodyValidationResolver> {
        if (scoped) {
            check(startup.isEmpty() && serverStarts.isEmpty() && observers.isEmpty() && prepare() == null) {
                "An API group supports typed body validation extensions. Install listener extensions on the server."
            }
        }
        return bodyValidationResolvers.toList()
    }

    /**
     * Start a server-wide resource before binding the listener. The handle is closed after
     * all I/O loops stop, or if server startup fails after the resource was started.
     */
    fun onServerStart(start: () -> AutoCloseable) {
        serverStarts.add(start)
    }

    /** Observe transport events alongside the application's existing observer. */
    fun observe(observer: ServerObserver) {
        observers.add(observer)
    }

    /** Observe accepted headers before any request hook can respond early. */
    fun onRequestStart(hook: (RequestContext) -> Unit) {
        requestStart.add(hook)
    }

    /** Called after valid request headers and before routing or consuming the body. */
    fun onRequest(hook: (RequestContext) -> RequestDecision) {
        request.add(hook)
    }

    /** Called after the standalone router selects a handler and before that handler starts. */
    fun onRoute(hook: (RequestContext, MatchedRoute) -> RequestDecision) {
        route.add(hook)
    }

    fun onResponseHeaders(hook: (RequestContext, ResponseHeaders) -> Unit) {
        headers.add(hook)
    }

    /** Called once when an accepted request finishes writing or terminates early. */
    fun onComplete(hook: (RequestContext, ExchangeCompletion) -> Unit) {
        complete.add(hook)
    }

    internal fun startWith(create: () -> ExtensionStartup) {
        if (startup.isEmpty()) return
        val context = create()
        try {
            for (hook in startup) context.hook()
        } finally {
            context.finish()
        }
    }

    internal fun serverStartHooks(): List<() -> AutoCloseable> = serverStarts.toList()

    internal val hasServerObservers: Boolean get() = observers.isNotEmpty()

    internal fun prepareObserver(applicationObserver: ServerObserver?): ServerObserver? =
        combineObservers(applicationObserver, observers)

    internal fun prepare(): RequestHooks? = if (
        requestStart.isEmpty() && request.isEmpty() && route.isEmpty() && headers.isEmpty() && complete.isEmpty()
    ) {
        null
    } else {
        RequestHooks(requestStart.toList(), request.toList(), route.toList(), headers.toList(), complete.toList())
    }
}

internal class RequestHooks(
    val requestStart: List<(RequestContext) -> Unit>,
    val request: List<(RequestContext) -> RequestDecision>,
    val route: List<(RequestContext, MatchedRoute) -> RequestDecision>,
    val headers: List<(RequestContext, ResponseHeaders) -> Unit>,
    val complete: List<(RequestContext, ExchangeCompletion) -> Unit>,
)

internal class ExtensionExchange(private val hooks: RequestHooks, request: Request) {
    private val context = RequestContext(request).also { request.extensionContext = it }
    private val started: TimeMark? = if (hooks.complete.isEmpty()) null else TimeSource.Monotonic.markNow()
    var status: Int? = null

    fun beforeRequest(): Response? {
        for (index in hooks.requestStart.indices) notifyObserver { hooks.requestStart[index](context) }
        return decide(hooks.request) { it(context) }
    }

    fun beforeRoute(request: Request, method: String, template: String): Response? {
        context.request = request
        if (hooks.route.isEmpty() && hooks.headers.isEmpty() && hooks.complete.isEmpty()) {
            context.freezeAttributes()
            return null
        }
        val route = MatchedRoute(method, template)
        context.route = route
        val response = decide(hooks.route) { it(context, route) }
        context.freezeAttributes()
        return response
    }

    fun beforeDelegatedHandler() = context.freezeAttributes()

    fun response(response: Response): Response {
        context.freezeAttributes()
        if (hooks.headers.isEmpty()) return response
        val headers = ResponseHeaders(response)
        for (hook in hooks.headers) hook(context, headers)
        return headers.apply()
    }

    fun complete(outcome: ExchangeOutcome) {
        context.freezeAttributes()
        val duration = started?.elapsedNow()?.inWholeNanoseconds ?: return
        val result = ExchangeCompletion(status, duration, outcome)
        for (hook in hooks.complete) notifyObserver { hook(context, result) }
    }

    private inline fun notifyObserver(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // An observation callback must not interrupt serving or transport cleanup.
        }
    }

    private inline fun <Hook> decide(hooks: List<Hook>, invoke: (Hook) -> RequestDecision): Response? {
        return try {
            for (hook in hooks) {
                val decision = invoke(hook)
                if (decision is RequestDecision.Respond) return decision.response
            }
            null
        } catch (_: Exception) {
            emptyResponse(500)
        }
    }
}
