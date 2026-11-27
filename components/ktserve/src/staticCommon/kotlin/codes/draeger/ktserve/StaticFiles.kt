package codes.draeger.ktserve

private const val MAX_FILE_BYTES = 1024 * 1024
private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
private const val MAX_ENTRIES = 4096
private const val MAX_DEPTH = 16

/**
 * Snapshot [directory] during server configuration and serve its files under the literal URL path [at].
 * Register multiple directories with separate calls. Directory URLs ending in `/` serve `index.html`.
 * The snapshot skips hidden entries and symlinks. Responses remain subject to [ServerLimits.responseBodyBytes].
 */
fun Routes.staticFiles(directory: String, at: String = "/") = staticFiles(directory, at, cacheControl = null)

/** Serve a static directory with a [cacheControl] policy for its representations. */
fun Routes.staticFiles(directory: String, at: String = "/", cacheControl: String?) {
    require(directory.isNotEmpty() && '\u0000' !in directory) { "Expected a static directory path" }
    require(at.startsWith('/') && '\u0000' !in at) { "Expected an absolute URL path for the static directory" }
    val cachePolicy = cacheControl?.let { value ->
        require(value.isNotBlank()) { "Expected a Cache-Control value" }
        HttpField("Cache-Control", value).also { it.requireApplicationField() }
    }
    val segments = at.removePrefix("/").removeSuffix("/").split('/')
    val prefix = if (at == "/") "" else {
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid URL path for the static directory: $at"
        }
        segments.joinToString("/", prefix = "/", transform = ::staticUrlSegment)
    }
    val snapshot = StaticFileSnapshot(cachePolicy)
    loadStaticFiles(directory, snapshot)
    snapshot.register(this, prefix)
}

internal expect fun loadStaticFiles(directory: String, snapshot: StaticFileSnapshot)

internal class StaticFileSnapshot(private val cachePolicy: HttpField?) {
    private val files = mutableListOf<StaticFile>()
    private var entries = 0
    private var totalBytes = 0

    fun checkDepth(depth: Int, path: String) {
        check(depth <= MAX_DEPTH) { "Directory depth exceeds $MAX_DEPTH at '$path'" }
    }

    fun visitEntry() {
        check(++entries <= MAX_ENTRIES) { "Directory exceeds $MAX_ENTRIES entries" }
    }

    fun addFile(path: String, name: String, length: Long, read: (Int) -> ByteArray) {
        check(length in 0..MAX_FILE_BYTES.toLong()) { "File '$path' exceeds $MAX_FILE_BYTES bytes" }
        val size = length.toInt()
        check(totalBytes + size <= MAX_TOTAL_BYTES) { "File contents exceed $MAX_TOTAL_BYTES bytes" }
        val bytes = read(size)
        check(bytes.size == size) { "Could not read all $size bytes of '$path'" }
        totalBytes += size
        files.add(StaticFile(path, contentType(name), bytes, cachePolicy))
    }

    fun register(routes: Routes, prefix: String) {
        for (file in files) {
            val path = prefix + file.path
            routes.get(path, file::response)
            if (path.endsWith("/index.html")) {
                val directory = path.removeSuffix("index.html")
                routes.get(directory, file::response)
                if (directory != "/") {
                    val response = redirect(directory)
                    routes.fallbackGet(directory.removeSuffix("/")) { request ->
                        request.query?.let { redirect("$directory?$it") } ?: response
                    }
                }
            }
        }
    }
}

private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html; charset=UTF-8"
    "txt" -> "text/plain; charset=UTF-8"
    "css" -> "text/css; charset=UTF-8"
    "js", "mjs" -> "text/javascript; charset=UTF-8"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}

internal fun staticUrlSegment(name: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in name.encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%').append(hex[value shr 4]).append(hex[value and 0xF])
        }
    }
}
