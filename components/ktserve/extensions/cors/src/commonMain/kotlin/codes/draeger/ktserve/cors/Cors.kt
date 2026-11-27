package codes.draeger.ktserve.cors

import codes.draeger.ktserve.KtServeExtensionDsl
import codes.draeger.ktserve.RequestAttribute
import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.ResponseHeaders
import codes.draeger.ktserve.ServerExtension
import codes.draeger.ktserve.extension
import codes.draeger.ktserve.response

private const val ORIGIN = "Origin"
private const val REQUEST_METHOD = "Access-Control-Request-Method"
private const val REQUEST_HEADERS = "Access-Control-Request-Headers"
private const val ALLOW_ORIGIN = "Access-Control-Allow-Origin"
private const val ALLOW_METHODS = "Access-Control-Allow-Methods"
private const val ALLOW_HEADERS = "Access-Control-Allow-Headers"
private const val ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials"

private val defaultMethods = setOf("GET", "HEAD", "POST")
private val preflightResult = RequestAttribute<Preflight>("codes.draeger.ktserve.cors.preflight")
private val acceptedPreflight = RequestDecision.Respond(response(ByteArray(0), status = 204))
private val deniedPreflight = RequestDecision.Respond(response(ByteArray(0), status = 403))

/** Options for a server-wide CORS policy. Origins and HTTP methods are matched exactly. */
@KtServeExtensionDsl
class CorsConfiguration internal constructor() {
    private val origins = linkedSetOf<String>()
    private val methods = linkedSetOf<String>()
    private val headers = linkedSetOf<String>()
    private var anyOrigin = false
    var allowCredentials: Boolean = false

    /** Allow serialized HTTP(S) origins such as `https://app.example`. */
    fun allowOrigins(vararg origins: String) {
        require(origins.all { it.isHttpOrigin() }) { "Expected serialized HTTP(S) origins without a path" }
        this.origins.addAll(origins)
    }

    /** Allow any origin with the CORS wildcard. */
    fun allowAnyOrigin() {
        anyOrigin = true
    }

    /** Allow the supplied methods. GET, HEAD and POST are used when none are specified. */
    fun allowMethods(vararg methods: String) {
        require(methods.all { it != "*" && it.isToken() }) { "Expected explicit HTTP methods" }
        this.methods.addAll(methods)
    }

    /** Allow the supplied request header names, compared without regard to ASCII case. */
    fun allowHeaders(vararg names: String) {
        require(names.all { it != "*" && it.isToken() }) { "Expected explicit HTTP header names" }
        names.mapTo(headers) { it.lowercase() }
    }

    internal fun build(): CorsPolicy {
        require(!anyOrigin || !allowCredentials) { "A wildcard origin cannot be combined with credentials" }
        require(!anyOrigin || origins.isEmpty()) { "Choose either a wildcard or an explicit list of origins" }
        return CorsPolicy(
            origins.toSet(),
            anyOrigin,
            if (methods.isEmpty()) defaultMethods else methods.toSet(),
            headers.toSet(),
            allowCredentials,
        )
    }
}

/**
 * Install a server-wide CORS policy. Install it before other early request guards so it can
 * answer browser preflights. Origins are denied by default until an allowlist or wildcard is configured.
 */
fun cors(configure: CorsConfiguration.() -> Unit): ServerExtension {
    val policy = CorsConfiguration().apply(configure).build()
    return extension("codes.draeger.ktserve.cors") {
        onRequest { context ->
            val request = context.request
            if (request.method != "OPTIONS") return@onRequest RequestDecision.Continue
            val methods = request.headerValues(REQUEST_METHOD)
            if (methods.isEmpty()) return@onRequest RequestDecision.Continue
            val origins = request.headerValues(ORIGIN)
            if (origins.isEmpty()) return@onRequest RequestDecision.Continue

            val origin = policy.preflight(origins, methods, request.headerValues(REQUEST_HEADERS))
            context[preflightResult] = Preflight(origin)
            if (origin == null) deniedPreflight else acceptedPreflight
        }

        onResponseHeaders { context, headers ->
            val request = context.request
            val options = request.method == "OPTIONS"
            headers.varyByCors(options)

            val preflight = context[preflightResult]
            val origin = if (preflight != null) {
                preflight.origin
            } else {
                val origins = request.headerValues(ORIGIN)
                if (options && request.headerValues(REQUEST_METHOD).isNotEmpty()) null
                else policy.allowedOrigin(origins, request.method)
            }

            if (origin == null) headers.remove(ALLOW_ORIGIN) else headers.set(ALLOW_ORIGIN, origin)
            if (origin != null && policy.allowCredentials) headers.set(ALLOW_CREDENTIALS, "true")
            else headers.remove(ALLOW_CREDENTIALS)

            if (preflight?.origin != null) {
                headers.set(ALLOW_METHODS, policy.advertisedMethods)
                val allowedHeaders = policy.advertisedHeaders
                if (allowedHeaders == null) headers.remove(ALLOW_HEADERS) else headers.set(ALLOW_HEADERS, allowedHeaders)
            } else {
                headers.remove(ALLOW_METHODS)
                headers.remove(ALLOW_HEADERS)
            }
        }
    }
}

private class Preflight(val origin: String?)

internal class CorsPolicy(
    private val origins: Set<String>,
    private val anyOrigin: Boolean,
    private val methods: Set<String>,
    private val headers: Set<String>,
    val allowCredentials: Boolean,
) {
    val advertisedMethods = methods.joinToString(", ")
    val advertisedHeaders = headers.takeIf { it.isNotEmpty() }?.joinToString(", ")

    fun allowedOrigin(origins: List<String>, method: String): String? {
        if (method !in methods) return null
        val origin = origins.singleOrNull() ?: return null
        if (anyOrigin) return "*".takeIf { origin == "null" || origin.isHttpOrigin() }
        return origin.takeIf { it in this.origins }
    }

    fun preflight(origins: List<String>, methods: List<String>, requestedHeaders: List<String>): String? {
        val method = methods.singleOrNull() ?: return null
        val origin = allowedOrigin(origins, method) ?: return null
        val headersAllowed = requestedHeaders.all { line ->
            line.split(',').all { rawName ->
                val name = rawName.trim(' ', '\t')
                name.isToken() && name.lowercase() in headers
            }
        }
        return origin.takeIf { headersAllowed }
    }
}

private fun ResponseHeaders.varyByCors(options: Boolean) {
    val existing = values("Vary")
    if (existing.isEmpty()) {
        set("Vary", if (options) "$ORIGIN, $REQUEST_METHOD, $REQUEST_HEADERS" else ORIGIN)
        return
    }

    val names = linkedMapOf<String, String>()
    for (line in existing) {
        for (field in line.split(',')) {
            val name = field.trim(' ', '\t')
            if (name == "*") return
            if (name.isNotEmpty()) names.getOrPut(name.lowercase()) { name }
        }
    }
    names.getOrPut(ORIGIN.lowercase()) { ORIGIN }
    if (options) {
        names.getOrPut(REQUEST_METHOD.lowercase()) { REQUEST_METHOD }
        names.getOrPut(REQUEST_HEADERS.lowercase()) { REQUEST_HEADERS }
    }
    set("Vary", names.values.joinToString(", "))
}

private fun String.isToken(): Boolean =
    isNotEmpty() && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#\$%&'*+-.^_`|~" }

private fun String.isHttpOrigin(): Boolean {
    val start = when {
        startsWith("https://") -> 8
        startsWith("http://") -> 7
        else -> return false
    }
    if (length == start) return false
    val end = if (this[start] == '[') {
        val closing = indexOf(']', start + 1)
        val colon = indexOf(':', start + 1)
        if (closing < 0 || colon !in (start + 1 until closing)) return false
        val validAddress = (start + 1 until closing).all { index ->
            val ch = this[index]
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' || ch in ":."
        }
        if (!validAddress) return false
        closing + 1
    } else {
        val hostEnd = indexOf(':', start).takeIf { it >= 0 } ?: length
        val validHost = (start until hostEnd).all { index ->
            val ch = this[index]
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._"
        }
        if (hostEnd == start || !validHost) return false
        hostEnd
    }
    if (end == length) return true
    if (this[end] != ':') return false
    val port = substring(end + 1)
    val number = port.toIntOrNull() ?: return false
    return number in 0..65535 && port.all { it in '0'..'9' }
}
