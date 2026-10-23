package cat.android.project

import cat.android.project.io.jsonCodec as gson
import cat.android.project.io.jsonObject as obj
import cat.android.project.io.jsonText as text
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.Versions
import org.gradle.tooling.*
import org.gradle.tooling.model.gradle.GradleBuild
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** These wire DTOs intentionally contain no Gradle/AGP proxies or editor state. */
data class ProjectModule(
    val buildRoot: String, val path: String, val name: String, val directory: String,
    val agp: String?, val projectType: String?, val variants: List<String>,
    val modelVersions: Map<String, String>, val issues: List<String>
) : Serializable
data class ProjectStructure(val modules: List<ProjectModule>, val buildRoots: List<String>) : Serializable

/** Runs in the build JVM. Do not capture client objects or use Gson in this action. */
internal class ProjectStructureAction : BuildAction<ProjectStructure>, Serializable {
    override fun execute(controller: BuildController): ProjectStructure {
        val modules = mutableListOf<ProjectModule>()
        val roots = linkedSetOf<String>()
        fun visit(build: GradleBuild) {
            val root = build.buildIdentifier.rootDir.canonicalPath
            if (!roots.add(root)) return
            for (project in build.projects) {
                val issues = mutableListOf<String>()
                val versions = controller.findModel(project, Versions::class.java)
                var basic: BasicAndroidProject? = null
                if (versions != null) {
                    try {
                        basic = controller.findModel(project, BasicAndroidProject::class.java)
                    } catch (error: RuntimeException) {
                        issues.add("basic-model: ${error.message}")
                    }
                }
                modules.add(ProjectModule(root, project.path, project.name, project.projectDirectory.canonicalPath,
                    versions?.agp, basic?.projectType?.name, basic?.variants?.map { it.name }.orEmpty(),
                    versions?.versions?.mapValues { (_,version) -> "${version.major}.${version.minor}" }.orEmpty(), issues))
            }
            build.includedBuilds.forEach { visit(it) }
        }
        visit(controller.buildModel)
        return ProjectStructure(modules, roots.toList())
    }
}

/** One owned connection/cancellation scope; never manages shared daemon lifetime. */
class GradleTooling(private val testInstallation: Path? = null) : AutoCloseable {
    private val cancellation = GradleConnector.newCancellationTokenSource()
    private val shutdown = Thread({ cancellation.cancel() }, "android-project-tooling-cancel")
    private var connector: GradleConnector? = null
    private var connection: ProjectConnection? = null
    private var connectedRoot: Path? = null
    init { Runtime.getRuntime().addShutdownHook(shutdown) }
    private fun connect(root: Path): ProjectConnection {
        val canonicalRoot=root.toRealPath()
        connection?.let {
            if (connectedRoot != canonicalRoot) throw ProjectFailure("A Tooling scope cannot switch projects", "input")
            return it
        }
        connectedRoot=canonicalRoot
        val connector = GradleConnector.newConnector().forProjectDirectory(root.toFile()).useBuildDistribution()
        testInstallation?.let { connector.useInstallation(it.toFile()) }
        System.getenv("GRADLE_USER_HOME")?.let { connector.useGradleUserHomeDir(Path.of(it).toFile()) }
        this.connector = connector
        return connector.connect().also { connection = it }
    }
    private fun configure(operation: LongRunningOperation, args: List<String>, env: Map<String,String>, output: OutputStream) {
        operation.withArguments(args).setEnvironmentVariables(System.getenv() + env)
            .setStandardOutput(output).setStandardError(System.err).setColorOutput(false)
            .withCancellationToken(cancellation.token())
            .addProgressListener(org.gradle.tooling.ProgressListener { event ->
                System.err.println("tooling: ${event.description}")
            })
        System.getenv("JAVA_HOME")?.let { home -> operation.setJavaHome(Path.of(home).toFile()) }
    }
    private fun <T> await(timeout: Long, start: (ResultHandler<T>) -> Unit): T {
        val done = CountDownLatch(1)
        val result = AtomicReference<T>()
        val failed = AtomicReference<GradleConnectionException>()
        start(object : ResultHandler<T> {
            override fun onComplete(value: T) { result.set(value); done.countDown() }
            override fun onFailure(failure: GradleConnectionException) { failed.set(failure); done.countDown() }
        })
        if (!done.await(timeout, TimeUnit.SECONDS)) {
            cancellation.cancel()
            done.await(5, TimeUnit.SECONDS)
            throw ProjectFailure("Gradle Tooling API operation timed out", "timeout")
        }
        failed.get()?.let { error ->
            val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.distinct().take(8).joinToString("\n")
            throw ProjectFailure(messages.ifEmpty { "Gradle Tooling API operation failed" }, "gradle")
        }
        return result.get()
    }
    fun build(root: Path, task: String, arguments: List<String>, env: Map<String,String>, timeout: Long) {
        val launcher = connect(root).newBuild().forTasks(task)
        configure(launcher, arguments, env, System.err)
        await<Void?>(timeout) { launcher.run(it) }
    }
    fun project(root: Path, script: Path, timeout: Long): Pair<ProjectStructure,String> {
        val output = ByteArrayOutputStream()
        val connection = connect(root)
        val action = connection.action(ProjectStructureAction())
        // Model sync is independent of Preview task configuration-cache policy.
        configure(action, listOf("--no-configuration-cache", "--no-configure-on-demand", "--console=plain"), emptyMap(), System.err)
        val structure = await<ProjectStructure>(timeout) { action.run(it) }
        val buildInputs = structure.buildRoots.associateWith { projectInputSignature(Path.of(it)) }
        // Task-mode evaluation preserves KMP Variant API lifecycle semantics.
        val supplement = connection.newBuild().forTasks("help")
        configure(supplement, listOf("--init-script", script.toString(), "--no-configuration-cache", "--no-configure-on-demand", "--console=plain"), emptyMap(), output)
        await<Void?>(timeout) { supplement.run(it) }
        if (buildInputs.any { (build, before) -> before != projectInputSignature(Path.of(build)) })
            throw ProjectFailure("Included build inputs changed during project sync; retry", "stale-model")
        return structure to output.toString(Charsets.UTF_8)
    }
    /** Cancel this operation, without stopping Gradle or Kotlin daemons. */
    fun cancel() { cancellation.cancel() }
    override fun close() {
        cancel()
        try { connection?.close() } finally {
            connector?.disconnect()
            Runtime.getRuntime().removeShutdownHook(shutdown)
        }
    }
}

internal fun projectTargets(output: String): List<com.google.gson.JsonObject> {
    var inside = false
    val lines = output.lineSequence().filter { line ->
        when (line.trim()) {
            "===FLAVORS_START===" -> { inside = true; false }
            "===FLAVORS_END===" -> { inside = false; false }
            else -> inside && line.startsWith(":")
        }
    }
    return lines.map { line ->
        val fields = line.trim().split('|')
        if (fields.size != 16) throw ProjectFailure("Invalid Variant API supplement record", "protocol")
        fun values(index: Int, separator: String) = fields[index].split(separator).filter { it.isNotEmpty() }
        val components = fields[13].split(',').filter { it.isNotEmpty() }.map { record ->
            val encoded = record.split(':')
            if (encoded.size != 3) throw ProjectFailure("Invalid component record", "protocol")
            fun decode(index: Int) = String(java.util.Base64.getDecoder().decode(encoded[index]), Charsets.UTF_8)
            obj("kind" to decode(0), "name" to decode(1), "sourceRoots" to decode(2).split(';').filter { it.isNotEmpty() })
        }
        obj("modulePath" to fields[0], "moduleRoot" to fields[1], "buildRoot" to fields[2],
            "moduleName" to fields[0].removePrefix(":"), "variant" to fields[3], "applicationId" to fields[4],
            "sourceRoots" to values(5,";"), "buildType" to fields[6],
            "productFlavors" to values(7,","), "preferredBuildType" to (fields[8] == "true"),
            "preferredProductFlavors" to values(9,","), "pluginId" to fields[10], "testApplicationId" to fields[11],
            "namespace" to fields[12], "components" to components, "projectType" to fields[14], "debuggable" to (fields[15] == "true"))
    }.toList()
}

internal fun synchronizeProject(project: Path, script: Path, timeout: Long): com.google.gson.JsonObject {
    val root = project.toAbsolutePath().normalize()
    if (!Files.isRegularFile(root.resolve("gradle/wrapper/gradle-wrapper.properties")))
        throw ProjectFailure("Project has no Gradle wrapper distribution", "input")
    val before = projectInputSignature(root)
    val (structure, supplement) = GradleTooling().use { it.project(root, script, timeout) }
    val targets = projectTargets(supplement).distinctBy { listOf(it.text("buildRoot"), it.text("modulePath"), it.text("variant")) }
    val moduleIds = structure.modules.map { it.buildRoot to it.path }.toSet()
    if (targets.any { it.text("buildRoot") to it.text("modulePath") !in moduleIds })
        throw ProjectFailure("Variant supplement contains an unknown build/module", "model")
    if (targets.isEmpty() && structure.modules.any { it.agp != null })
        throw ProjectFailure("Android modules exist but Variant API supplement is empty", "model")
    targets.forEach { it.add("modelBuildRoots", gson.toJsonTree(structure.buildRoots)) }
    val after = projectInputSignature(root)
    if (before != after) throw ProjectFailure("Project inputs changed during model sync; retry", "stale-model")
    return obj("schemaVersion" to 1, "kind" to "android-project", "status" to "ready",
        "projectRoot" to root.toString(), "transport" to "gradle-tooling-api", "modules" to structure.modules,
        "buildRoots" to structure.buildRoots, "targets" to targets,
        "capabilities" to obj("agpV2Basic" to structure.modules.any { it.projectType != null },
            "variantSupplement" to true, "dependencyResolution" to false))
}
