package cat.android.project

import cat.android.project.tooling.TargetModel
import com.android.builder.model.v2.ide.*
import com.android.builder.model.v2.models.VariantDependencies
import java.lang.reflect.Proxy
import kotlin.test.*
import org.junit.jupiter.api.Test

class DependencyTest {
    private inline fun <reified T> model(vararg fields: Pair<String,Any?>): T {
        val values=mapOf(*fields)
        return Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _, method, _ ->
            if(method.name in values) values[method.name] else error("Unexpected model access ${method.name}")
        } as T
    }
    private fun target(root:String="/entry",path:String=":app",variant:String="debug",type:String="debug",flavors:List<String> = emptyList()) =
        TargetModel.TargetData(path,"$root/app",root,variant,"",emptyList(),type,flavors,false,emptyList(),
            "com.android.library","","example",emptyList(),"library",true)
    private fun library(key:String,build:String,path:String,variant:String?="release",flavors:Map<String,String> = emptyMap()):Library {
        val info=model<ProjectInfo>("getBuildId" to build,"getProjectPath" to path,"getBuildType" to "release",
            "getProductFlavors" to flavors,"getAttributes" to (variant?.let { mapOf("com.android.build.gradle.internal.attributes.VariantAttr" to it) } ?: emptyMap<String,String>()),
            "getCapabilities" to listOf("example:test-fixtures:1"),"isTestFixtures" to true)
        return model("getType" to LibraryType.PROJECT,"getProjectInfo" to info,"getKey" to key)
    }
    @Test fun `selection is composite safe explicit and follows default preferences`() {
        val a=target();val release=target(variant="release",type="release")
        val included=target(root="/included")
        assertEquals(listOf(a,included),selectTargets(listOf(release,a,included),emptyList()))
        assertEquals(listOf(release,included),selectTargets(listOf(a,release,included),listOf(TargetSelection("/entry",":app","release"))))
        assertEquals(listOf(a),selectTargets(listOf(a),listOf(TargetSelection("/missing",":app","debug"))))
        assertEquals(listOf(a),selectTargets(listOf(a),listOf(TargetSelection("/entry",":app","removedVariant"))))
        assertFailsWith<ProjectFailure> { selectTargets(listOf(a),listOf(TargetSelection("/entry",":app","debug"),TargetSelection("/entry",":app","debug"))) }
        val preferred=target(variant="prodRelease",type="release",flavors=listOf("prod"))
        val preferredCopy=TargetModel.TargetData(preferred.modulePath,preferred.moduleRoot,preferred.buildRoot,preferred.variant,"",emptyList(),
            preferred.buildType,preferred.productFlavors,true,listOf("prod"),preferred.pluginId,"","",emptyList(),"library",false)
        assertEquals("prodRelease",selectTargets(listOf(a,preferredCopy),emptyList()).single().variant)
    }
    @Test fun `graphs keep scope directness capabilities exact build and resolved variant`() {
        val child=model<GraphItem>("getKey" to "child","getDependencies" to emptyList<GraphItem>())
        val parent=model<GraphItem>("getKey" to "parent","getDependencies" to listOf(child))
        val artifact=model<ArtifactDependencies>("getCompileDependencies" to listOf(parent),"getRuntimeDependencies" to listOf(child),
            "getUnresolvedDependencies" to emptyList<UnresolvedDependency>())
        val deps=model<VariantDependencies>("getName" to "debug","getMainArtifact" to artifact,
            "getLibraries" to mapOf("parent" to library("parent",":included",":lib"),"child" to library("child",":",":lib")))
        val snapshot=dependencySnapshot(target(),deps,mapOf(":" to "/entry",":included" to "/other"),
            listOf(target(path=":lib",variant="release",type="release"),target(root="/other",path=":lib",variant="release",type="release")),emptyMap())
        assertEquals("ready",snapshot.status)
        assertEquals(3,snapshot.edges.size)
        assertEquals("/other",snapshot.edges[0].buildRoot)
        assertEquals("release",snapshot.edges[0].variant) // Actual producer differs from consumer debug.
        assertFalse(snapshot.edges[1].direct)
        assertTrue(snapshot.edges[2].direct)
        assertEquals("runtime",snapshot.edges[2].scope)
        assertTrue(snapshot.edges.all { it.testFixtures && it.capabilities.isNotEmpty() })
    }
    @Test fun `ambiguous flavored variants and unknown builds remain explicit`() {
        val node=model<GraphItem>("getKey" to "lib","getDependencies" to emptyList<GraphItem>())
        val artifact=model<ArtifactDependencies>("getCompileDependencies" to listOf(node),"getRuntimeDependencies" to null,
            "getUnresolvedDependencies" to listOf(model<UnresolvedDependency>("getName" to "missing","getCause" to "not found")))
        fun snapshot(build:String,flavors:Map<String,String>) = dependencySnapshot(target(),
            model<VariantDependencies>("getName" to "debug","getMainArtifact" to artifact,
                "getLibraries" to mapOf("lib" to library("lib",build,":lib",null,flavors))),
            mapOf(":" to "/entry"),listOf(target(path=":lib",variant="freeRelease",type="release",flavors=listOf("free")),
                target(path=":lib",variant="paidRelease",type="release",flavors=listOf("paid"))),mapOf(("/entry" to ":lib") to listOf("tier")))
        val resolved=snapshot(":",mapOf("tier" to "paid"))
        assertEquals("paidRelease",resolved.edges.single().variant)
        assertEquals("partial",resolved.status) // Unresolved external dependency and runtime graph retained.
        assertTrue(resolved.issues.any { "missing" in it })
        assertEquals("unmapped-variant",snapshot(":",emptyMap()).edges.single().status)
        assertEquals("unknown-build",snapshot(":unknown",emptyMap()).edges.single().status)
    }
}
