package codes.draeger.ktserve

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView

internal actual fun loadStaticFiles(directory: String, snapshot: StaticFileSnapshot) {
    val root = Path.of(directory).toAbsolutePath()
    val parent = root.parent
    Files.newDirectoryStream(parent ?: root).use { stream ->
        check(stream is SecureDirectoryStream<Path>) {
            "The JVM filesystem provider for '$root' must support SecureDirectoryStream to serve static files"
        }
        if (parent == null) {
            stream.collectFiles(snapshot, "", 0)
        } else {
            stream.newDirectoryStream(root.fileName, NOFOLLOW_LINKS).use { child ->
                child.collectFiles(snapshot, "", 0)
            }
        }
    }
}

private val readWithoutFollowingLinks = setOf<OpenOption>(READ, NOFOLLOW_LINKS)

private fun SecureDirectoryStream<Path>.collectFiles(snapshot: StaticFileSnapshot, prefix: String, depth: Int) {
    snapshot.checkDepth(depth, prefix)
    for (entry in this) {
        snapshot.visitEntry()
        val relative = entry.fileName
        val name = relative.toString()
        if (name.startsWith('.')) continue

        val path = "$prefix/${staticUrlSegment(name)}"
        val attributes = checkNotNull(getFileAttributeView(relative, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)) {
            "The JVM filesystem provider cannot inspect '$path' safely"
        }
        val info = attributes.readAttributes()
        when {
            info.isDirectory -> newDirectoryStream(relative, NOFOLLOW_LINKS).use { child ->
                child.collectFiles(snapshot, path, depth + 1)
            }
            info.isRegularFile -> snapshot.addFile(path, name, info.size()) { length ->
                newByteChannel(relative, readWithoutFollowingLinks).use { file ->
                    val current = attributes.readAttributes()
                    check(current.isRegularFile && current.fileKey() == info.fileKey()) {
                        "Could not verify '$path' after opening it"
                    }
                    val bytes = ByteArray(length)
                    val target = ByteBuffer.wrap(bytes)
                    while (target.hasRemaining()) {
                        check(file.read(target) > 0) { "Could not read all $length bytes of '$path'" }
                    }
                    bytes
                }
            }
        }
    }
}
