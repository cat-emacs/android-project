package cat.android.project

import cat.android.project.io.jsonText as text
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ToolingTest {
    @TempDir lateinit var root: Path
    private fun toolingFiles(): Pair<Path,Path> {
        val script=root.resolve("model.init.gradle")
        val plugin=root.resolve("android-project-tooling.jar")
        ProjectModule::class.java.getResourceAsStream("/cat/android/project/project.init.gradle")!!.use { Files.copy(it,script) }
        ProjectModule::class.java.getResourceAsStream("/cat/android/project/android-project-tooling.jar")!!.use { Files.copy(it,plugin) }
        return script to plugin
    }
    @Test fun `typed DTOs serialize delimiters and unicode without a text protocol`() {
        val target=cat.android.project.tooling.TargetModel.TargetData(":app", "/path|with;delimiters", "/root", "debug",
            "com.example",listOf("src/中文|a;b"),"debug",listOf("a,b"),true,emptyList(),"com.android.application",
            "com.example.test","com.example",listOf(cat.android.project.tooling.TargetModel.ComponentData("main","debug",listOf("src/中文|a;b"))),
            "application",true)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bytes).use { it.writeObject(ProjectStructure(emptyList(),listOf("/root"),listOf(target))) }
        val result=java.io.ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() as ProjectStructure }
        assertEquals(target.sourceRoots,result.targets.single().sourceRoots)
        assertEquals(target.moduleRoot,result.targets.single().moduleRoot)
    }
    @Test fun `root discovery never fetches Android or tooling supplement models`() {
        val emptyProjects=emptyList<org.gradle.tooling.model.gradle.BasicGradleProject>()
        fun build(directory:Path, included:List<org.gradle.tooling.model.gradle.GradleBuild>): org.gradle.tooling.model.gradle.GradleBuild {
            val id=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(org.gradle.tooling.model.BuildIdentifier::class.java)) { _,method,_ ->
                when(method.name) { "getRootDir" -> directory.toFile(); else -> error("Unexpected identifier access ${method.name}") }
            }
            return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(org.gradle.tooling.model.gradle.GradleBuild::class.java)) { _,method,_ ->
                when(method.name) { "getBuildIdentifier" -> id; "getIncludedBuilds" -> java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                        arrayOf(org.gradle.tooling.model.DomainObjectSet::class.java)) { _,method,_ ->
                        when(method.name) { "iterator" -> included.iterator(); else -> error("Unexpected collection access ${method.name}") }
                    };
                    "getProjects" -> emptyProjects; else -> error("Unexpected build access ${method.name}") }
            } as org.gradle.tooling.model.gradle.GradleBuild
        }
        val child=root.resolve("included").createDirectories()
        val graph=build(root,listOf(build(child,emptyList()),build(child,emptyList())))
        val controller=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(org.gradle.tooling.BuildController::class.java)) { _,method,_ ->
            when(method.name) { "getBuildModel" -> graph; else -> error("Discovery fetched model: ${method.name}") }
        } as org.gradle.tooling.BuildController
        assertEquals(listOf(root.toRealPath().toString(),child.toRealPath().toString()),BuildRootsAction().execute(controller))
    }
    @Test fun `project signatures are independent of Preview assets and build outputs`() {
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"model\"")
        val before=projectInputSignature(root)
        val settings=root.resolve("settings.gradle.kts")
        val attributes=Files.readAttributes(settings,java.nio.file.attribute.BasicFileAttributes::class.java)
        val changed=try { Files.getAttribute(settings,"unix:ctime").toString() }
            catch (_:UnsupportedOperationException) { attributes.creationTime().toString() }
            catch (_:IllegalArgumentException) { attributes.creationTime().toString() }
        val legacy=listOf(settings.toAbsolutePath().normalize(),attributes.size(),attributes.lastModifiedTime(),changed,null).joinToString("\u0000")
        assertEquals(cat.android.project.io.sha256(legacy.toByteArray()),before)
        Files.createDirectories(root.resolve("build"))
        root.resolve("build/Generated.kt").writeText("generated")
        root.resolve("README.md").writeText("documentation")
        assertEquals(before,projectInputSignature(root))
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"changed\"")
        assertNotEquals(before,projectInputSignature(root))
    }
    @Test fun `typed model discovers included builds without executing help or user tasks`() {
        val home=Path.of(System.getProperty("project.test.gradle.home"))
        val child=root.resolve("included").createDirectories()
        root.resolve("settings.gradle").writeText("rootProject.name='entry'\nincludeBuild('included')\n")
        child.resolve("settings.gradle").writeText("rootProject.name='included'\n")
        val failTasks="tasks.configureEach { doFirst { throw new GradleException('MUST_NOT_EXECUTE_TASKS') } }\n"
        root.resolve("build.gradle").writeText(failTasks)
        child.resolve("build.gradle").writeText(failTasks)
        val (script,plugin)=toolingFiles()
        val structure=GradleTooling(home).use { it.project(root,script,plugin,120) }
        assertEquals(setOf(root.toRealPath().toString(),child.toRealPath().toString()),structure.buildRoots.toSet())
        assertEquals(2,structure.modules.size)
        assertEquals(root.toRealPath().toString(),structure.buildPaths[":"])
        assertEquals(child.toRealPath().toString(),structure.buildPaths[":included"])
        assertTrue(structure.targets.isEmpty())
    }
    @Test fun `compiled plugin returns typed Android supplement without delimiter loss or task execution`() {
        val home=Path.of(System.getProperty("project.test.gradle.home"))
        root.resolve("settings.gradle").writeText("rootProject.name='typed-provider'\n")
        root.resolve("buildSrc/src/main/groovy").createDirectories()
        root.resolve("buildSrc/src/main/resources/META-INF/gradle-plugins").createDirectories()
        root.resolve("buildSrc/build.gradle").writeText("plugins { id 'groovy' }\ndependencies { implementation gradleApi(); implementation localGroovy() }\n")
        root.resolve("buildSrc/src/main/resources/META-INF/gradle-plugins/com.android.application.properties")
            .writeText("implementation-class=FakeAndroidPlugin\n")
        root.resolve("src/with|pipes;and中文").createDirectories()
        root.resolve("buildSrc/src/main/groovy/FakeAndroidPlugin.groovy").writeText("""
            import org.gradle.api.*
            class VariantData {
                String name = 'debug', buildType = 'debug'
                List productFlavors = []
                def applicationId, namespace, sources
                boolean debuggable = true
            }
            class BuildTypeData { boolean isDefault = false }
            class FakeAndroidPlugin implements Plugin<Project> {
                void apply(Project p) {
                    def variant = new VariantData(
                        applicationId: p.providers.provider {
                            def counter = p.file('build/supplement-configurations')
                            counter.parentFile.mkdirs()
                            counter.text = ((counter.exists() ? counter.text.toInteger() : 0) + 1).toString()
                            'com.example|typed'
                        },
                        namespace: p.providers.provider { 'com.example' },
                        sources: new Expando(java: new Expando(static: p.providers.provider {
                            [p.layout.projectDirectory.dir('src/with|pipes;and中文')]
                        }), kotlin: null))
                    p.extensions.add('android', new Expando(
                        buildTypes: new Expando(findByName: { name -> new BuildTypeData() }),
                        productFlavors: []))
                    p.extensions.add('androidComponents', new Expando(
                        selector: { -> new Expando(all: { -> 'all' }) },
                        onVariants: { selector, action -> action.call(variant) }))
                }
            }
        """.trimIndent())
        root.resolve("build.gradle").writeText("apply plugin: 'com.android.application'\ntasks.configureEach { doFirst { throw new GradleException('TASK_EXECUTED') } }\n")
        val (script,plugin)=toolingFiles()
        val structure=GradleTooling(home).use { it.project(root,script,plugin,120) }
        val target=structure.targets.single()
        assertEquals("com.example|typed",target.applicationId)
        assertEquals("1",root.resolve("build/supplement-configurations").readText())
        assertEquals(listOf("src/with|pipes;and中文"),target.components.single().sourceRoots)
        assertFalse(target.preferredBuildType)
        assertEquals(listOf("VariantKgpSupplement"),structure.modules.single().fetchedModels)
    }
    @Test fun `lightweight discovery still rejects included build inputs changed during model fetch`() {
        root.resolve("settings.gradle").writeText("rootProject.name='entry'\nincludeBuild('included')\n")
        val included=root.resolve("included").createDirectories()
        included.resolve("settings.gradle").writeText("rootProject.name='included'\n")
        val (script,plugin)=toolingFiles()
        script.appendText("\ngradle.projectsEvaluated { if (rootProject.name == 'included') { new File(rootProject.projectDir, 'changed.properties').text = 'model-fetch' } }\n")
        val failure=assertFailsWith<ProjectFailure> {
            GradleTooling(Path.of(System.getProperty("project.test.gradle.home"))).use {
                it.project(root,script,plugin,120)
            }
        }
        assertEquals("stale-model",failure.kind)
        assertTrue(included.resolve("changed.properties").exists())
    }
    @Test fun `missing injected plugin fails instead of silently returning empty Android targets`() {
        root.resolve("settings.gradle").writeText("rootProject.name='no-plugin'\n")
        val script=root.resolve("empty.init.gradle")
        script.writeText("// no tooling plugin\n")
        val failure=assertFailsWith<ProjectFailure> {
            GradleTooling(Path.of(System.getProperty("project.test.gradle.home"))).use {
                it.project(root,script,root.resolve("absent.jar"),120)
            }
        }
        assertTrue(failure.message!!.contains("Project tooling model unavailable"))
    }
    @Test fun `tooling action fetches real Gradle build and preview task honors configuration cache`() {
        // Reuse the exact Gradle distribution running these tests; no network/Android SDK required.
        val home=System.getProperty("project.test.gradle.home") ?: return
        Files.createDirectories(root.resolve("gradle/wrapper"))
        root.resolve("settings.gradle").writeText("rootProject.name = 'tooling-fixture'\ninclude(':lib')\n")
        Files.createDirectories(root.resolve("lib"))
        root.resolve("build.gradle").writeText("tasks.register('probe') { doLast { println 'TOOLING_PROBE_OK' } }\n" +
            "tasks.register('broken') { doLast { throw new GradleException('EXPECTED_TOOLING_FAILURE') } }\n" +
            "tasks.register('slow') { doLast { Thread.sleep(30000) } }\n")
        val (script,plugin)=toolingFiles()
        val structure=GradleTooling(Path.of(home)).use { tooling ->
            val result=tooling.project(root,script,plugin,120)
            val failure=assertFailsWith<ProjectFailure> { tooling.build(root.resolve("lib"),":probe",emptyList(),emptyMap(),120) }
            assertEquals("input",failure.kind)
            result
        }
        assertTrue(structure.modules.any { it.path == ":lib" })
        assertTrue(structure.targets.isEmpty())
        GradleTooling(Path.of(home)).use { it.build(root,":probe",listOf("--configuration-cache"),emptyMap(),120) }
        assertTrue(root.resolve(".gradle/configuration-cache").exists())
        val failure=assertFailsWith<ProjectFailure> {
            GradleTooling(Path.of(home)).use { it.build(root,":broken",emptyList(),emptyMap(),120) }
        }
        assertTrue(failure.message!!.contains("EXPECTED_TOOLING_FAILURE"))
        val timeout=assertFailsWith<ProjectFailure> {
            GradleTooling(Path.of(home)).use { it.build(root,":slow",emptyList(),emptyMap(),1) }
        }
        assertEquals("timeout",timeout.kind)
        // Cancellation closes the connection but leaves the build service usable.
        GradleTooling(Path.of(home)).use { it.build(root,":probe",emptyList(),emptyMap(),120) }
        assertTrue(Path.of(home).exists())
    }
}
