package cat.android.project

import cat.android.project.io.jsonCodec as gson
import cat.android.project.io.jsonHash
import cat.android.project.io.jsonObject as obj
import cat.android.project.tooling.TargetModel
import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.VariantDependencies
import com.google.gson.JsonObject
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import java.io.Serializable
import java.nio.file.Path

/** All reusable state is copied DTO data, never a Gradle model proxy. */
data class ModuleDimensions(val buildRoot: String, val modulePath: String, val names: List<String>) : Serializable
internal data class MetadataBaseline(val structure: ProjectStructure) {
    fun checkInputs() {
        val current = try {
            structure.inputSignatures.keys == structure.buildRoots.toSet() &&
                structure.inputSignatures.all { (root, signature) -> projectInputSignature(Path.of(root)) == signature }
        } catch (_: Exception) { false }
        if (!current) throw ProjectFailure("Metadata inputs changed; full sync required", "stale-baseline")
    }
}
internal fun metadataContext(root: Path, structure: ProjectStructure): JsonObject {
    // Keep only the DTO fields needed for exact target/producer mapping.
    val metadata = structure.copy(dependencies = emptyList(), selectionIssues = emptyList(),
        modules = structure.modules.map { it.copy(agp = null, projectType = null, variants = emptyList(),
            modelVersions = emptyMap(), issues = emptyList(), fetchedModels = emptyList()) },
        targets = structure.targets.map { target -> TargetModel.TargetData(target.modulePath,target.moduleRoot,
            target.buildRoot,target.variant,"",emptyList(),target.buildType,target.productFlavors,false,emptyList(),
            "","","",emptyList(),"",false) })
    val payload = obj("version" to 1, "consumer" to "core-0.1.6-agp-9.3.0",
        "javaHome" to System.getProperty("java.home"), "gradleUserHome" to System.getenv("GRADLE_USER_HOME"),
        "projectRoot" to root.toString(), "structure" to metadata)
    return obj("id" to jsonHash(payload), "payload" to payload)
}
internal fun readMetadataBaseline(root: Path, context: JsonObject): MetadataBaseline {
    try {
        val payload = context.getAsJsonObject("payload")
        if (payload["version"].asInt != 1 || payload["consumer"].asString != "core-0.1.6-agp-9.3.0" ||
            payload["javaHome"].asString != System.getProperty("java.home") ||
            payload["gradleUserHome"] != obj("value" to System.getenv("GRADLE_USER_HOME"))["value"] ||
            context["id"].asString != jsonHash(payload) ||
            Path.of(payload["projectRoot"].asString).toFile().canonicalPath != root.toFile().canonicalPath)
            throw IllegalArgumentException("Metadata context identity mismatch")
        val structure = gson.fromJson(payload["structure"],ProjectStructure::class.java)
        require(structure.buildRoots.distinct().size == structure.buildRoots.size)
        require(structure.buildRoots.all { Path.of(it).isAbsolute })
        require(structure.targets.all { it.components.isEmpty() && it.sourceRoots.isEmpty() })
        require(structure.targets.map { Triple(it.buildRoot,it.modulePath,it.variant) }.distinct().size == structure.targets.size)
        require(structure.buildRoots.contains(root.toFile().canonicalPath))
        require(structure.modules.map { it.buildRoot to it.path }.distinct().size == structure.modules.size)
        require(structure.targets.all { target -> structure.modules.any {
            it.buildRoot == target.buildRoot && it.path == target.modulePath && it.directory == target.moduleRoot
        } })
        require(structure.buildPaths.values.toSet() == structure.buildRoots.toSet())
        return MetadataBaseline(structure)
    } catch (error: Exception) {
        throw ProjectFailure("Invalid metadata context: ${error.message}", "stale-baseline")
    }
}
internal fun fetchDependencies(controller: BuildController, projects: Map<Pair<String,String>,BasicGradleProject>,
    requested: List<TargetModel.TargetData>, buildPaths: Map<String,String>, targets: List<TargetModel.TargetData>,
    dimensions: Map<Pair<String,String>,List<String>>): List<DependencySnapshot> = requested.map { target ->
    try {
        val model = controller.findModel(projects.getValue(target.buildRoot to target.modulePath),
            VariantDependencies::class.java,ModelBuilderParameter::class.java) { parameter ->
            parameter.variantName = target.variant
            parameter.dontBuildRuntimeClasspath = false
            parameter.dontBuildUnitTestRuntimeClasspath = true
            parameter.dontBuildScreenshotTestRuntimeClasspath = true
            parameter.dontBuildAndroidTestRuntimeClasspath = true
            parameter.dontBuildTestFixtureRuntimeClasspath = true
            parameter.dontBuildHostTestRuntimeClasspath = emptyMap()
            parameter.additionalArtifactsInModel = false
        }
        if (model == null) DependencySnapshot(target.buildRoot,target.modulePath,target.variant,"unsupported",emptyList(),
            listOf("AGP V2 VariantDependencies unavailable"))
        else dependencySnapshot(target,model,buildPaths,targets,dimensions)
    } catch (error: RuntimeException) {
        DependencySnapshot(target.buildRoot,target.modulePath,target.variant,"failed",emptyList(),
            listOf(error.message ?: error.javaClass.name))
    }
}
internal data class DependencyResult(val matches: Boolean, val dependencies: List<DependencySnapshot>) : Serializable

/** No Versions, AndroidProject, AndroidDsl or supplement model requests on this path. */
internal class ProjectDependenciesAction(private val metadata: ProjectStructure, private val selections: List<TargetSelection>) :
    BuildAction<DependencyResult>, Serializable {
    override fun execute(controller: BuildController): DependencyResult {
        val roots = linkedSetOf<String>()
        val projects = mutableMapOf<Pair<String,String>,BasicGradleProject>()
        fun visit(build: GradleBuild) {
            val root = build.buildIdentifier.rootDir.canonicalPath
            if (!roots.add(root)) return
            build.projects.forEach { projects[root to it.path] = it }
            build.includedBuilds.forEach { visit(it) }
        }
        visit(controller.buildModel)
        val matches = roots == metadata.buildRoots.toSet() &&
            projects.keys == metadata.modules.map { it.buildRoot to it.path }.toSet() &&
            metadata.modules.all { projects.getValue(it.buildRoot to it.path).projectDirectory.canonicalPath == it.directory }
        if (!matches) return DependencyResult(false,emptyList())
        val requested = requestedDependencyTargets(metadata.targets,selections,"requested")
        if (requested.size != selections.size) return DependencyResult(false,emptyList())
        return DependencyResult(true,fetchDependencies(controller,projects,requested,metadata.buildPaths,metadata.targets,
            metadata.dimensions.associate { (it.buildRoot to it.modulePath) to it.names }))
    }
}

/** A stale baseline is explicit: the caller must request fresh metadata instead. */
fun synchronizeDependencies(root: Path, context: JsonObject, selections: List<TargetSelection>, timeout: Long = 600): JsonObject {
    val baseline = readMetadataBaseline(root,context)
    if (requestedDependencyTargets(baseline.structure.targets,selections,"requested").size != selections.size)
        throw ProjectFailure("Requested target missing from metadata; full sync required", "stale-baseline")
    val dependencies = GradleTooling().use { it.dependencies(root,baseline,selections,timeout) }
    return obj("schemaVersion" to 1,"kind" to "android-project-dependencies","status" to "ready",
        "projectRoot" to root.toAbsolutePath().normalize().toString(),"metadataId" to context["id"].asString,
        "dependencies" to dependencies)
}
