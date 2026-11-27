package codes.draeger.ktserve.accesslog

internal actual fun writeAccessLogToStderr(bytes: ByteArray, length: Int) {
    val stderr = System.err
    stderr.write(bytes, 0, length)
    check(!stderr.checkError()) { "Could not write the access log to stderr" }
}
