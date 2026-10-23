package cat.android.project.io

import java.io.BufferedWriter
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Same-directory staging, replacement and cleanup; a failed writer preserves the old file. */
fun atomicWrite(path: Path, write: (BufferedWriter) -> Unit) {
    val target = path.toAbsolutePath().normalize()
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".cat-write-", ".tmp")
    try {
        Files.newBufferedWriter(temporary).use(write)
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}

/** Sorted traversal, without following directory symlinks. The root itself is never excluded. */
fun walkFiles(root: Path, excludeDirectory: (Path) -> Boolean = { false }): List<Path> {
    val result = mutableListOf<Path>()
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return result
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir != root && excludeDirectory(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            result.add(file)
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult = throw error
    })
    return result.sortedBy { it.toString() }
}

/** Follow file links for target metadata; callers can add link identity separately. */
fun fileStamp(path: Path): List<Any>? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java).let {
        val changed = try { Files.getAttribute(path, "unix:ctime").toString() }
            catch (_: UnsupportedOperationException) { it.creationTime().toString() }
            catch (_: IllegalArgumentException) { it.creationTime().toString() }
        listOf(it.size(), it.lastModifiedTime().toString(), changed)
    }
} catch (_: NoSuchFileException) { null }

fun symbolicLinkTarget(path: Path): String? =
    if (Files.isSymbolicLink(path)) Files.readSymbolicLink(path).toString() else null

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

/** Content identity for a jar or directory, including relative file names in traversal order. */
fun contentHash(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val paths = if (Files.isDirectory(path)) walkFiles(path) else listOf(path)
    paths.forEach { file ->
        digest.update(path.relativize(file).toString().toByteArray(Charsets.UTF_8))
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    return digest.digest().hex()
}
