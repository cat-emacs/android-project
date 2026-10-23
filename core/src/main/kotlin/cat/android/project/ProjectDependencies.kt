package cat.android.project

import cat.android.project.tooling.TargetModel
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.LibraryType
import com.android.builder.model.v2.models.VariantDependencies
import java.io.Serializable

/** Selection is scoped by canonical owning build, never an ambiguous module name. */
data class TargetSelection(val buildRoot: String, val modulePath: String, val variant: String) : Serializable

data class ProjectDependency(
    val buildRoot: String?, val modulePath: String, val variant: String?,
    val buildPath: String, val buildType: String?, val productFlavors: Map<String,String>,
    val attributes: Map<String,String>, val capabilities: List<String>, val testFixtures: Boolean,
    val scope: String, val direct: Boolean, val status: String
) : Serializable

data class DependencySnapshot(
    val buildRoot: String, val modulePath: String, val variant: String,
    val status: String, val edges: List<ProjectDependency>, val issues: List<String>
) : Serializable

internal fun selectTargets(targets: List<TargetModel.TargetData>, selections: List<TargetSelection>): List<TargetModel.TargetData> {
    val groups=targets.groupBy { it.buildRoot to it.modulePath }
    if(selections.map { it.buildRoot to it.modulePath }.distinct().size != selections.size)
        throw ProjectFailure("Duplicate module selections","input")
    return groups.map { (id,candidates) ->
        val explicit=selections.find { it.buildRoot to it.modulePath == id && candidates.any { t -> t.variant == it.variant } }
        if (explicit != null) candidates.single { it.variant == explicit.variant }
        else candidates.sortedWith { a,b ->
            val dimensions=candidates.minOf { it.productFlavors.size }
            fun key(t: TargetModel.TargetData): List<String> = listOf(if(t.preferredBuildType) "0" else "1") +
                t.productFlavors.take(dimensions).map { if(it in t.preferredProductFlavors) "0" else "1" } +
                listOf(if(t.buildType == "debug") "0" else "1") + t.productFlavors.take(dimensions) + t.buildType
            val ak=key(a); val bk=key(b)
            ak.indices.firstNotNullOfOrNull { i -> ak[i].compareTo(bk[i]).takeIf { it != 0 } } ?: 0
        }.first()
    }
}

internal fun requestedDependencyTargets(targets: List<TargetModel.TargetData>, selections: List<TargetSelection>, mode: String): List<TargetModel.TargetData> =
    when(mode) {
        "none" -> emptyList()
        "selected" -> selectTargets(targets,selections)
        "requested" -> {
            if(selections.map { it.buildRoot to it.modulePath }.distinct().size != selections.size)
                throw ProjectFailure("Duplicate module requests", "input")
            targets.filter { target -> selections.any {
                it.buildRoot == target.buildRoot && it.modulePath == target.modulePath && it.variant == target.variant
            } }
        }
        else -> throw ProjectFailure("Invalid dependency mode: $mode", "input")
    }

internal fun dependencySnapshot(
    source: TargetModel.TargetData, model: VariantDependencies, buildPaths: Map<String,String>,
    targets: List<TargetModel.TargetData>, dimensions: Map<Pair<String,String>,List<String>>
): DependencySnapshot {
    require(model.name == source.variant) { "Dependency model returned another variant: ${model.name}" }
    val result=mutableListOf<ProjectDependency>()
    val issues=mutableListOf<String>()
    fun visit(roots: List<GraphItem>, scope: String) {
        val seen=mutableSetOf<String>()
        val direct=roots.map { it.key }.toSet()
        val pending=ArrayDeque(roots)
        while(pending.isNotEmpty()) {
            val node=pending.removeFirst()
            if(!seen.add(node.key)) continue
            val library=model.libraries[node.key]
            if(library == null) issues.add("$scope: missing library ${node.key}")
            else if(library.type == LibraryType.PROJECT) result.add(projectEdge(library,scope,node.key in direct,buildPaths,targets,dimensions))
            pending.addAll(node.dependencies)
        }
    }
    visit(model.mainArtifact.compileDependencies,"compile")
    val runtime=model.mainArtifact.runtimeDependencies
    if(runtime == null) issues.add("Main runtime dependency graph unavailable") else visit(runtime,"runtime")
    issues.addAll(model.mainArtifact.unresolvedDependencies.map { "${it.name}: ${it.cause ?: "unresolved"}" })
    if(result.any { it.status != "resolved" }) issues.add("Some project identities or variants could not be mapped")
    return DependencySnapshot(source.buildRoot,source.modulePath,source.variant,
        if(issues.isEmpty()) "ready" else "partial",result,issues.distinct())
}

private fun projectEdge(library: Library, scope: String, direct: Boolean, paths: Map<String,String>,
                        targets: List<TargetModel.TargetData>, dimensions: Map<Pair<String,String>,List<String>>): ProjectDependency {
    val info=library.projectInfo ?: throw IllegalStateException("Project library has no projectInfo: ${library.key}")
    val build=paths[info.buildId]
    val candidates=targets.filter { it.buildRoot == build && it.modulePath == info.projectPath }
    val explicit=info.attributes["com.android.build.gradle.internal.attributes.VariantAttr"]
    val matched=if(explicit != null) candidates.filter { it.variant == explicit } else candidates.filter { target ->
        val names=dimensions[build to info.projectPath]
        target.buildType == (info.buildType ?: "") && names != null && names.size == target.productFlavors.size &&
            names.zip(target.productFlavors).all { (dimension,flavor) -> info.productFlavors[dimension] == flavor }
    }
    val variant=matched.singleOrNull()?.variant
    return ProjectDependency(build,info.projectPath,variant,info.buildId,info.buildType,
        info.productFlavors.toMap(),info.attributes.toMap(),info.capabilities.toList(),info.isTestFixtures,
        scope,direct,when { build == null -> "unknown-build"; candidates.isEmpty() -> "non-android-or-unmodeled";
            variant == null -> "unmapped-variant"; else -> "resolved" })
}
