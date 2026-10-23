package cat.android.project

import com.google.gson.*
import java.nio.file.*
import cat.android.project.io.fileStamp
import cat.android.project.io.sha256
import cat.android.project.io.symbolicLinkTarget
import cat.android.project.io.walkFiles
import kotlin.io.path.*

class ProjectFailure(message: String, val kind: String = "error") : RuntimeException(message)
/** Conservative project inputs, independent of Preview assets or editor state. */
fun projectInputSignature(root: Path): String {
    val skip = setOf(".git", ".gradle", ".idea", "build", "node_modules")
    val suffixes = setOf("kt", "kts", "java", "groovy", "gradle", "properties", "toml", "xml", "jar", "aar")
    val records = walkFiles(root) { it.fileName.toString() in skip }.filter { it.extension in suffixes }.map { path ->
        (listOf(path.toAbsolutePath().normalize().toString()) +
            (fileStamp(path) ?: throw NoSuchFileException(path.toString())) +
            listOf(symbolicLinkTarget(path))).joinToString("\u0000")
    }
    return sha256(records.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
}

/** Project sync keeps all script resources in the core artifact, never in a consumer. */
fun synchronizeProject(root: Path, timeout: Long = 600, selections: List<TargetSelection> = emptyList()): JsonObject {
    val directory = Files.createTempDirectory("android-project-sync-")
    val script = directory.resolve("project.init.gradle")
    val plugin = directory.resolve("android-project-tooling.jar")
    val cleanup = Thread({ Files.deleteIfExists(script); Files.deleteIfExists(plugin); Files.deleteIfExists(directory) }, "android-project-resource-cleanup")
    Runtime.getRuntime().addShutdownHook(cleanup)
    try {
        val stream = ProjectModule::class.java.getResourceAsStream("/cat/android/project/project.init.gradle")
            ?: throw ProjectFailure("Missing project collector resource", "setup")
        stream.use { Files.copy(it,script) }
        val pluginStream = ProjectModule::class.java.getResourceAsStream("/cat/android/project/android-project-tooling.jar")
            ?: throw ProjectFailure("Missing project tooling plugin", "setup")
        pluginStream.use { Files.copy(it,plugin) }
        return synchronizeProject(root,script,plugin,timeout,selections)
    } finally {
        Runtime.getRuntime().removeShutdownHook(cleanup)
        Files.deleteIfExists(script)
        Files.deleteIfExists(plugin)
        Files.deleteIfExists(directory)
    }
}
