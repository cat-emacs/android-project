package cat.android.project

import com.google.gson.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.*

class ProjectFailure(message: String, val kind: String = "error") : RuntimeException(message)
internal val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
internal fun obj(vararg fields: Pair<String,Any?>): JsonObject = gson.toJsonTree(mapOf(*fields)).asJsonObject
internal fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw ProjectFailure("Invalid string: $key", "protocol")
    it.asString
}

/** Conservative project inputs, independent of Preview assets or editor state. */
fun projectInputSignature(root: Path): String {
    val records = mutableListOf<String>()
    val skip = setOf(".git", ".gradle", ".idea", "build", "node_modules")
    if (Files.isDirectory(root)) Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir != root && dir.fileName.toString() in skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (path.extension in setOf("kt","kts","java","groovy","gradle","properties","toml","xml","jar","aar")) {
                val current = Files.readAttributes(path, BasicFileAttributes::class.java)
                val changed = try { Files.getAttribute(path,"unix:ctime").toString() }
                    catch (_: UnsupportedOperationException) { current.creationTime().toString() }
                    catch (_: IllegalArgumentException) { current.creationTime().toString() }
                records.add(listOf(path.toAbsolutePath().normalize(),current.size(),current.lastModifiedTime(),changed,
                    if (Files.isSymbolicLink(path)) Files.readSymbolicLink(path) else null).joinToString("\u0000"))
            }
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult = throw error
    })
    return MessageDigest.getInstance("SHA-256").digest(records.sorted().joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Project sync keeps all script resources in the core artifact, never in a consumer. */
fun synchronizeProject(root: Path, timeout: Long = 600): JsonObject {
    val directory = Files.createTempDirectory("android-project-sync-")
    val script = directory.resolve("project.init.gradle")
    val cleanup = Thread({ Files.deleteIfExists(script); Files.deleteIfExists(directory) }, "android-project-resource-cleanup")
    Runtime.getRuntime().addShutdownHook(cleanup)
    try {
        val stream = ProjectModule::class.java.getResourceAsStream("/cat/android/project/project.init.gradle")
            ?: throw ProjectFailure("Missing project collector resource", "setup")
        stream.use { Files.copy(it,script) }
        return synchronizeProject(root,script,timeout)
    } finally {
        Runtime.getRuntime().removeShutdownHook(cleanup)
        Files.deleteIfExists(script)
        Files.deleteIfExists(directory)
    }
}
