@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve

import codes.draeger.ktserve.staticfiles.interop.ktserve_static_duplicate
import codes.draeger.ktserve.staticfiles.interop.ktserve_static_entry_kind
import codes.draeger.ktserve.staticfiles.interop.ktserve_static_open_child
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fdopendir
import platform.posix.fstat
import platform.posix.open
import platform.posix.read
import platform.posix.readdir
import platform.posix.set_posix_errno
import platform.posix.stat

internal actual fun loadStaticFiles(directory: String, snapshot: StaticFileSnapshot) {
    val root = open(directory.trimEnd('/').ifEmpty { "/" }, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
    check(root >= 0) { "Could not open directory '$directory' (errno $errno)" }
    try {
        scanDirectory(root, "", 0, snapshot)
    } finally {
        close(root)
    }
}

private fun scanDirectory(descriptor: Int, prefix: String, depth: Int, snapshot: StaticFileSnapshot): Unit = memScoped {
    snapshot.checkDepth(depth, prefix)
    val duplicate = ktserve_static_duplicate(descriptor)
    check(duplicate >= 0) { "Could not duplicate directory descriptor (errno $errno)" }
    val directory = fdopendir(duplicate)
    if (directory == null) {
        val failure = errno
        close(duplicate)
        error("Could not open directory stream (errno $failure)")
    }
    try {
        val info = alloc<stat>()
        while (true) {
            set_posix_errno(0)
            val entry = readdir(directory)
            if (entry == null) {
                check(errno == 0) { "Could not list '$prefix' (errno $errno)" }
                break
            }
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            snapshot.visitEntry()
            if (name.startsWith('.')) continue
            val path = "$prefix/${staticUrlSegment(name)}"

            val kind = ktserve_static_entry_kind(descriptor, name)
            check(kind >= 0) { "Could not inspect '$path' (errno $errno)" }
            if (kind != S_IFDIR && kind != S_IFREG) continue

            val child = ktserve_static_open_child(descriptor, name)
            check(child >= 0) { "Could not open '$path' (errno $errno)" }
            try {
                check(fstat(child, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == kind) {
                    "Could not verify '$path' after opening it"
                }
                if (kind == S_IFDIR) {
                    scanDirectory(child, path, depth + 1, snapshot)
                } else {
                    snapshot.addFile(path, name, info.st_size) { size -> readFile(child, path, size) }
                }
            } finally {
                close(child)
            }
        }
    } finally {
        // fdopendir owns the duplicate. The caller retains its original descriptor.
        closedir(directory)
    }
}

private fun readFile(descriptor: Int, path: String, size: Int): ByteArray {
    val bytes = ByteArray(size)
    // Borrow the managed buffer only while copying bytes from the open descriptor.
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < size) {
            val count = read(descriptor, pinned.addressOf(offset), (size - offset).convert())
            if (count < 0 && errno == EINTR) continue
            check(count > 0) { "Could not read all $size bytes of '$path'" }
            offset += count.toInt()
        }
    }
    return bytes
}
