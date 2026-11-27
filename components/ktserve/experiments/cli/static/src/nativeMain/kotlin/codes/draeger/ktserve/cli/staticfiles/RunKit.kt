@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.cli.staticfiles

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.errno
import platform.posix.execv
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.rmdir
import platform.posix.unlink

internal fun runSnippet(): Nothing {
    val temporary = getenv("TMPDIR")?.toKString()?.takeIf { it.startsWith('/') } ?: "/tmp"
    val directory = memScoped {
        mkdtemp("$temporary/ktserve-run.XXXXXXXX".cstr.getPointer(this))?.toKString()
    } ?: error("Could not create a temporary run directory (errno $errno)")
    val archive = "$directory/run-kit.tar.gz"
    try {
        writeRunKit(archive)
        val command = listOf("bash", "-c", BOOTSTRAP, "ktserve-run", directory, RUN_KIT_ID)
        memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
            for ((index, argument) in command.withIndex()) {
                argv[index] = argument.cstr.getPointer(this)
            }
            argv[command.size] = null
            // On success Bash takes over the process and owns cleanup of the private directory.
            execv("/bin/bash", argv)
        }
        error("Could not start Bash for ktserve run - (errno $errno)")
    } finally {
        unlink(archive)
        rmdir(directory)
    }
}

private fun writeRunKit(archive: String) {
    val bytes = embeddedRunKit()
    val file = fopen(archive, "wb") ?: error("Could not open the run kit (errno $errno)")
    val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() }
    val failed = ferror(file) != 0
    val closed = fclose(file) == 0
    check(written == bytes.size && !failed && closed) { "Could not write the run kit" }
}

private val BOOTSTRAP = """
    set -euo pipefail
    work=${'$'}1
    trap 'rm -rf -- "${'$'}work"' EXIT
    case "${'$'}(uname -s):${'$'}(uname -m)" in
        Linux:aarch64|Linux:arm64)
            printf '%s\n' 'ktserve run - requires a Kotlin/Native compiler host. Linux ARM64 is unsupported. Use Linux x86-64 or Apple Silicon macOS.' >&2
            exit 2
            ;;
    esac
    tar -xzf "${'$'}work/run-kit.tar.gz" -C "${'$'}work"
    core="${'$'}work/components/ktserve"
    chmod u+x "${'$'}core/gradlew"
    cache="${'$'}{XDG_CACHE_HOME:-${'$'}{HOME:?Set HOME or XDG_CACHE_HOME}/.cache}/ktserve/run/${'$'}2"
    app="${'$'}(KTSERVE_CLI_BUILD_ROOT="${'$'}cache" /bin/bash "${'$'}core/experiments/cli/run/run.sh" --build-only -)"
    rm -rf -- "${'$'}work"
    trap - EXIT
    exec "${'$'}app"
""".trimIndent()
