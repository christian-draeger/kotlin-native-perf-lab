package io.github.christian_draeger.ktserve

/** An HTTP header field. Multiple fields can have the same name. */
data class HttpField(val name: String, val value: String)

internal fun Char.isHttpToken(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "!#$%&'*+-.^`_|~"

private val managedFields = setOf(
    "connection", "content-length", "content-type", "keep-alive",
    "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
)

internal fun HttpField.requireApplicationField() {
    require(name.isNotEmpty() && name.all(Char::isHttpToken)) { "Invalid HTTP field name" }
    require(name.lowercase() !in managedFields) { "ktServe controls the $name response field" }
    require(value.all { it == '\t' || it in ' '..'~' }) { "Invalid HTTP field value" }
}

internal fun requireContentType(contentType: String?) {
    require(contentType == null || contentType.isNotBlank() && contentType.all { it in ' '..'~' }) {
        "Invalid response Content-Type"
    }
}
