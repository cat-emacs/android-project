package cat.android.project

import cat.android.project.io.jsonCodec as gson
import cat.android.project.io.jsonObject as obj
import cat.android.project.tooling.TargetModel
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.VariantDependencies
import org.gradle.tooling.model.gradle.BasicGradleProject
import com.android.builder.model.v2.models.AndroidDsl
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.Versions
import org.gradle.tooling.*
import org.gradle.tooling.model.gradle.GradleBuild
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
    val modelVersions: Map<String, String>, val issues: List<String>, val fetchedModels: List<String>
) : Serializable
data class ProjectStructure(val modules: List<ProjectModule>, val buildRoots: List<String>,
    val targets: List<TargetModel.TargetData>, val dependencies: List<DependencySnapshot> = emptyList(),
    val buildPaths: Map<String,String> = emptyMap(), val selectionIssues: List<String> = emptyList()) : Serializable

/** Discovery must not fetch Android or supplement models that the next action will read. */
internal class BuildRootsAction : BuildAction<List<String>>, Serializable {
    override fun execute(controller: BuildController): List<String> {
        val roots = linkedSetOf<String>()
        fun visit(build: GradleBuild) {
            if (!roots.add(build.buildIdentifier.rootDir.canonicalPath)) return
            build.includedBuilds.forEach { visit(it) }
        }
        visit(controller.buildModel)
        return roots.toList()
    }
}

/** Runs in the build JVM. Do not capture client objects or use Gson in this action. */
internal class ProjectStructureAction(private val selections: List<TargetSelection> = emptyList(),
    private val dependencyMode: String = "none") : BuildAction<ProjectStructure>, Serializable {
    override fun execute(controller: BuildController): ProjectStructure {
        val modules = mutableListOf<ProjectModule>()
        val roots = linkedSetOf<String>()
        val targets = mutableListOf<TargetModel.TargetData>()
        val projects = mutableMapOf<Pair<String,String>,BasicGradleProject>()
        val buildPaths = mutableMapOf<String,String>()
        val dimensions = mutableMapOf<Pair<String,String>,List<String>>()
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
                val supplement = controller.findModel(project, TargetModel::class.java)
                    ?: throw IllegalStateException("Project tooling model unavailable for $root${project.path}")
                projects[root to project.path] = project
                val previous = buildPaths.put(supplement.buildPath,root)
                check(previous == null || previous == root) { "Ambiguous Gradle build path ${supplement.buildPath}" }
                var android: AndroidProject? = null
                var dsl: AndroidDsl? = null
                if (basic != null) {
                    try { android = controller.findModel(project, AndroidProject::class.java) }
                    catch (error: RuntimeException) { issues.add("android-model: ${error.message}") }
                    try { dsl = controller.findModel(project, AndroidDsl::class.java) }
                    catch (error: RuntimeException) { issues.add("dsl-model: ${error.message}") }
                }
                if(dsl != null) dimensions[root to project.path] = dsl.flavorDimensions.toList()
                for (target in supplement.targets) {
                    check(target.buildRoot == root && target.modulePath == project.path &&
                        target.moduleRoot == project.projectDirectory.canonicalPath) { "Tooling model returned another module" }
                    val basicVariant = basic?.variants?.find { it.name == target.variant }
                    val androidVariant = android?.variants?.find { it.name == target.variant }
                    val buildType = basicVariant?.buildType ?: target.buildType
                    val buildDsl = dsl?.buildTypes?.find { it.name == buildType }
                    // Final IDs come from artifacts, never DSL namespace/defaultConfig guesses.
                    val mainId = androidVariant?.mainArtifact?.applicationId ?: target.applicationId
                    val testId = androidVariant?.deviceTestArtifacts?.get("androidTest")?.applicationId ?: target.testApplicationId
                    targets.add(TargetModel.TargetData(target.modulePath, target.moduleRoot, target.buildRoot, target.variant,
                        mainId, target.sourceRoots, buildType, basicVariant?.productFlavors ?: target.productFlavors,
                        buildDsl?.isDefault ?: target.preferredBuildType,
                        dsl?.productFlavors?.filter { it.isDefault == true }?.map { it.name } ?: target.preferredProductFlavors,
                        target.pluginId, testId, android?.namespace ?: target.namespace, target.components.map { TargetModel.ComponentData(it.kind,it.name,it.sourceRoots) },
                        target.projectType, buildDsl?.isDebuggable ?: target.debuggable))
                }
                modules.add(ProjectModule(root, project.path, project.name, project.projectDirectory.canonicalPath,
                    versions?.agp, basic?.projectType?.name, basic?.variants?.map { it.name }.orEmpty(),
                    versions?.versions?.mapValues { (_,version) -> "${version.major}.${version.minor}" }.orEmpty(), issues,
                    listOfNotNull("VariantKgpSupplement", basic?.let { "BasicAndroidProject" },
                        android?.let { "AndroidProject" }, dsl?.let { "AndroidDsl" })))
            }
            build.includedBuilds.forEach { visit(it) }
        }
        visit(controller.buildModel)
        val dependencyTargets = requestedDependencyTargets(targets,selections,dependencyMode)
        val dependencies = dependencyTargets.map { target ->
            val project = projects.getValue(target.buildRoot to target.modulePath)
            try {
                val model = controller.findModel(project,VariantDependencies::class.java,ModelBuilderParameter::class.java) { parameter ->
                    parameter.variantName = target.variant
                    parameter.dontBuildRuntimeClasspath = false
                    parameter.dontBuildUnitTestRuntimeClasspath = true
                    parameter.dontBuildScreenshotTestRuntimeClasspath = true
                    parameter.dontBuildAndroidTestRuntimeClasspath = true
                    parameter.dontBuildTestFixtureRuntimeClasspath = true
                    parameter.dontBuildHostTestRuntimeClasspath = emptyMap()
                    parameter.additionalArtifactsInModel = false
                }
                if(model == null) DependencySnapshot(target.buildRoot,target.modulePath,target.variant,"unsupported",emptyList(),
                    listOf("AGP V2 VariantDependencies unavailable"))
                else dependencySnapshot(target,model,buildPaths,targets,dimensions)
            } catch(error:RuntimeException) {
                DependencySnapshot(target.buildRoot,target.modulePath,target.variant,"failed",emptyList(),
                    listOf(error.message ?: error.javaClass.name))
            }
        }
        val selectionIssues=selections.filter { selection -> targets.none {
            it.buildRoot == selection.buildRoot && it.modulePath == selection.modulePath && it.variant == selection.variant
        } }.map { "Stale dependency selection ignored: $it" }
        return ProjectStructure(modules, roots.toList(), targets, dependencies, buildPaths, selectionIssues)
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
    fun project(root: Path, script: Path, plugin: Path, timeout: Long, selections: List<TargetSelection> = emptyList(), dependencyMode: String = "none"): ProjectStructure {
        val arguments = listOf("--init-script", script.toString(),
            "-Dcat.android.project.tooling.jar=${plugin.toAbsolutePath()}",
            "--no-configuration-cache", "--no-configure-on-demand", "--console=plain")
        val started = System.nanoTime()
        // Only Gradle structure is needed to establish included-build input guards.
        val discovery = connect(root).action(BuildRootsAction())
        configure(discovery, listOf("--no-configuration-cache", "--no-configure-on-demand", "--console=plain"),
            emptyMap(), System.err)
        val roots = await<List<String>>(timeout) { discovery.run(it) }
        val discoveredAt = System.nanoTime()
        val buildInputs = roots.associateWith { projectInputSignature(Path.of(it)) }
        val guardedAt = System.nanoTime()
        val action = connect(root).action(ProjectStructureAction(selections,dependencyMode))
        configure(action, arguments, emptyMap(), System.err)
        val structure = await<ProjectStructure>(timeout) { action.run(it) }
        val fetchedAt = System.nanoTime()
        if (roots.toSet() != structure.buildRoots.toSet() ||
            buildInputs.any { (build, before) -> before != projectInputSignature(Path.of(build)) })
            throw ProjectFailure("Included build inputs changed during project sync; retry", "stale-model")
        val checkedAt = System.nanoTime()
        fun millis(from: Long, to: Long) = (to - from) / 1_000_000
        System.err.println("sync-timing: discovery=${millis(started,discoveredAt)}ms " +
            "input-baseline=${millis(discoveredAt,guardedAt)}ms models=${millis(guardedAt,fetchedAt)}ms " +
            "input-check=${millis(fetchedAt,checkedAt)}ms dependencies=${structure.dependencies.size}")
        return structure
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

internal fun synchronizeProject(project: Path, script: Path, plugin: Path, timeout: Long, selections: List<TargetSelection> = emptyList(), dependencyMode: String = "none"): com.google.gson.JsonObject {
    val root = project.toAbsolutePath().normalize()
    if (!Files.isRegularFile(root.resolve("gradle/wrapper/gradle-wrapper.properties")))
        throw ProjectFailure("Project has no Gradle wrapper distribution", "input")
    val before = projectInputSignature(root)
    val structure = GradleTooling().use { it.project(root, script, plugin, timeout, selections, dependencyMode) }
    val targets = structure.targets.distinctBy { listOf(it.buildRoot, it.modulePath, it.variant) }
    val moduleIds = structure.modules.map { it.buildRoot to it.path }.toSet()
    if (targets.any { it.buildRoot to it.modulePath !in moduleIds })
        throw ProjectFailure("Variant supplement contains an unknown build/module", "model")
    if (targets.isEmpty() && structure.modules.any { it.agp != null })
        throw ProjectFailure("Android modules exist but Variant API supplement is empty", "model")
    val jsonTargets = targets.map { target ->
        gson.toJsonTree(target).asJsonObject.also {
            it.addProperty("moduleName", target.modulePath.removePrefix(":"))
            it.add("modelBuildRoots", gson.toJsonTree(structure.buildRoots))
            val dependency = structure.dependencies.find { d -> d.buildRoot == target.buildRoot && d.modulePath == target.modulePath && d.variant == target.variant }
            it.add("dependencies", gson.toJsonTree(dependency ?: DependencySnapshot(target.buildRoot,target.modulePath,target.variant,
                "not-requested",emptyList(),emptyList())))
        }
    }
    val after = projectInputSignature(root)
    if (before != after) throw ProjectFailure("Project inputs changed during model sync; retry", "stale-model")
    return obj("schemaVersion" to 1, "kind" to "android-project", "status" to "ready",
        "projectRoot" to root.toString(), "transport" to "gradle-tooling-api", "modules" to structure.modules,
        "buildRoots" to structure.buildRoots, "targets" to jsonTargets, "selectionIssues" to structure.selectionIssues,
        "capabilities" to obj("agpV2Basic" to structure.modules.any { it.projectType != null },
            "variantSupplement" to true, "typedToolingModel" to true,
            "agpV2Details" to structure.modules.any { "AndroidProject" in it.fetchedModels && "AndroidDsl" in it.fetchedModels },
            "dependencyResolution" to structure.dependencies.any { it.status == "ready" || it.status == "partial" },
            "dependencyScope" to "main-compile-runtime", "dependencyMode" to dependencyMode, "variantPropagation" to false))
}
