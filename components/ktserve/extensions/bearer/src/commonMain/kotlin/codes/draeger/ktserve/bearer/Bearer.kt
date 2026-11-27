package codes.draeger.ktserve.bearer

import codes.draeger.ktserve.Request
import codes.draeger.ktserve.RequestAttribute
import codes.draeger.ktserve.RequestDecision
import codes.draeger.ktserve.ServerExtension
import codes.draeger.ktserve.extension

/** Bearer extraction result. A present token still needs application verification. */
sealed interface BearerCredential {
    data object Absent : BearerCredential
    data object Invalid : BearerCredential

    /** The opaque credential, with its original case and padding. */
    class Present internal constructor(val token: String) : BearerCredential {
        override fun toString(): String = "BearerCredential.Present(<redacted>)"
    }
}

private val credentialAttribute = RequestAttribute<BearerCredential>("codes.draeger.ktserve.bearer.credential")

/** The credential extracted for this request by [bearer]. */
val Request.bearerCredential: BearerCredential
    get() = attribute(credentialAttribute) ?: BearerCredential.Absent

/** Extract an HTTP Authorization bearer credential according to RFC 6750 section 2.1. */
fun bearer(): ServerExtension = extension("codes.draeger.ktserve.bearer") {
    onRequest { exchange ->
        val credential = parseBearerCredential(exchange.request.headerValues("Authorization"))
        if (credential != BearerCredential.Absent) exchange[credentialAttribute] = credential
        RequestDecision.Continue
    }
}

internal fun parseBearerCredential(values: List<String>): BearerCredential {
    if (values.isEmpty()) return BearerCredential.Absent
    if (values.size != 1) return BearerCredential.Invalid

    val value = values[0]
    var schemeEnd = 0
    while (schemeEnd < value.length && value[schemeEnd].isAuthSchemeCharacter()) schemeEnd++
    if (schemeEnd == 0) return BearerCredential.Invalid
    if (schemeEnd != "Bearer".length || !value.startsWith("Bearer", ignoreCase = true)) {
        return BearerCredential.Absent
    }
    if (schemeEnd == value.length || value[schemeEnd] != ' ') return BearerCredential.Invalid

    var start = schemeEnd + 1
    while (start < value.length && value[start] == ' ') start++
    var end = start
    while (end < value.length && value[end].isBearerTokenCharacter()) end++
    if (end == start) return BearerCredential.Invalid
    while (end < value.length && value[end] == '=') end++
    if (end != value.length) return BearerCredential.Invalid

    return BearerCredential.Present(value.substring(start))
}

private fun Char.isAsciiAlphanumeric(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun Char.isAuthSchemeCharacter(): Boolean = isAsciiAlphanumeric() || this in "!#$%&'*+-.^`_|~"

private fun Char.isBearerTokenCharacter(): Boolean = isAsciiAlphanumeric() || this in "-._~+/"
