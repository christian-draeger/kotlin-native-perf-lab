@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.accesslog

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fwrite
import platform.posix.stderr

internal actual fun writeAccessLogToStderr(bytes: ByteArray, length: Int) {
    val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), length.convert(), stderr) }
    check(written.toLong() == length.toLong()) { "Could not write the access log to stderr" }
}
