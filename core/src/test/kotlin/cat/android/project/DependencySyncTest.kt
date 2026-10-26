package cat.android.project

import cat.android.project.tooling.TargetModel
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.gradle.BasicGradleProject
import org.gradle.tooling.model.gradle.GradleBuild
import com.android.builder.model.v2.models.VariantDependencies
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DependencySyncTest {
    @TempDir lateinit var root: Path
    private inline fun <reified T> proxy(crossinline get: (String,Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _,method,args -> get(method.name,args) } as T
    private fun structure(): ProjectStructure {
        val directory=root.toFile().canonicalPath
        val target=TargetModel.TargetData(":",directory,directory,"debug","",emptyList(),"debug",emptyList(),false,
            emptyList(),"com.android.library","","example",listOf(TargetModel.ComponentData("main","debug",listOf("src/main"))),"library",true)
        return ProjectStructure(listOf(ProjectModule(directory,":","entry",directory,null,null,emptyList(),emptyMap(),emptyList(),emptyList())),
            listOf(directory),listOf(target),buildPaths=mapOf(":" to directory),
            dimensions=listOf(ModuleDimensions(directory,":",emptyList())),inputSignatures=mapOf(directory to projectInputSignature(root)))
    }
    @Test fun `metadata context keeps mapping fields only and rejects tampering or other project`() {
        val original=structure()
        val context=metadataContext(root,original)
        val decoded=readMetadataBaseline(root,context)
        assertEquals("debug",decoded.structure.targets.single().variant)
        assertTrue(decoded.structure.targets.single().components.isEmpty())
        assertEquals(original.buildPaths,decoded.structure.buildPaths)
        assertEquals(original.dimensions,decoded.structure.dimensions)
        decoded.checkInputs()
        assertFailsWith<ProjectFailure> { readMetadataBaseline(root.resolve("other"),context) }
        context.getAsJsonObject("payload").addProperty("projectRoot","/other")
        assertFailsWith<ProjectFailure> { readMetadataBaseline(root,context) }
    }
    @Test fun `baseline checks include all discovered input roots`() {
        val included=root.resolve("included").createDirectories()
        val metadata=structure().copy(buildRoots=listOf(root.toString(),included.toString()),
            inputSignatures=mapOf(root.toString() to projectInputSignature(root),included.toString() to projectInputSignature(included)))
        val baseline=MetadataBaseline(metadata)
        baseline.checkInputs()
        included.resolve("build.gradle").writeText("// changed")
        assertEquals("stale-baseline",assertFailsWith<ProjectFailure> { baseline.checkInputs() }.kind)
    }
    @Test fun `dependency action requests only exact graphs and rejects changed structure`() {
        val metadata=structure()
        val project=proxy<BasicGradleProject> { name,_ -> when(name) {
            "getPath" -> ":"; "getProjectDirectory" -> root.toFile(); else -> error("Unexpected project access $name")
        } }
        fun build(projects:List<BasicGradleProject>): GradleBuild = proxy { name,_ -> when(name) {
            "getBuildIdentifier" -> proxy<BuildIdentifier> { key,_ -> if(key=="getRootDir") root.toFile() else error(key) }
            "getProjects" -> proxy<DomainObjectSet<BasicGradleProject>> { key,_ -> if(key=="iterator") projects.iterator() else error(key) }
            "getIncludedBuilds" -> proxy<DomainObjectSet<GradleBuild>> { key,_ -> if(key=="iterator") emptyList<GradleBuild>().iterator() else error(key) }
            else -> error("Unexpected build access $name")
        } }
        var graph=build(listOf(project))
        var requests=0
        val controller=proxy<BuildController> { name,args -> when(name) {
            "getBuildModel" -> graph
            "findModel" -> {
                assertEquals(VariantDependencies::class.java,args!![1])
                requests++
                null
            }
            else -> error("Unexpected metadata request $name")
        } }
        val selection=listOf(TargetSelection(root.toRealPath().toString(),":","debug"))
        val action=ProjectDependenciesAction(metadata,selection)
        val result=action.execute(controller)
        assertTrue(result.matches)
        assertEquals(1,requests)
        assertEquals("unsupported",result.dependencies.single().status)
        graph=build(emptyList())
        assertFalse(action.execute(controller).matches)
        assertEquals(1,requests)
        assertFalse(ProjectDependenciesAction(metadata,listOf(selection.single().copy(variant="removed"))).execute(controller).matches)
    }
}
