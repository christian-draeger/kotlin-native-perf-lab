package codes.draeger.ktserve

/** An HTTP header field. Multiple fields can have the same name. */
data class HttpField(val name: String, val value: String)

/** Immutable application fields that can be validated once and reused across responses. */
class PreparedResponseHeaders(vararg fields: HttpField) {
    internal val fields = fields.toList()
    internal val wireBytes = this.fields.sumOf { field ->
        field.requireApplicationField()
        field.name.length.toLong() + field.value.length + 4
    }
    internal val hasAllow = this.fields.any { it.name.equals("allow", ignoreCase = true) }
}

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
