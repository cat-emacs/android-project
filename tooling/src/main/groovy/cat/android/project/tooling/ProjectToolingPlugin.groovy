package cat.android.project.tooling

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import javax.inject.Inject

/** Variant/KGP supplement; all values are copied to wire DTOs before leaving Gradle. */
class ProjectToolingPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry registry
    @Inject ProjectToolingPlugin(ToolingModelBuilderRegistry registry) { this.registry = registry }
    void apply(Project project) {
        def flavorRecords = []
        def androidPluginIds = [
            'com.android.application',
            'com.android.library',
            'com.android.dynamic-feature',
            'com.android.test',
            'com.android.kotlin.multiplatform.library'
        ]

        def addRelativeDir = { proj, roots, dir ->
            if (dir != null && dir.isDirectory()) {
                roots.add(proj.projectDir.toPath().relativize(dir.toPath()).toString())
            }
        }

        def addSourceDirectorySet = { proj, roots, sourceDirectorySet ->
            sourceDirectorySet?.srcDirs?.each { dir ->
                addRelativeDir(proj, roots, dir)
            }
        }

        def addComponentSourceRoots = { proj, roots, component ->
            [component?.sources?.java, component?.sources?.kotlin].each { directories ->
                directories?.static?.getOrElse([])?.each { directory ->
                    addRelativeDir(proj, roots, directory.asFile)
                }
            }
        }

        def addKotlinMultiplatformAndroidSourceRoots = { proj, roots, variantName ->
            def kotlin = proj.extensions.findByName("kotlin")
            if (kotlin == null || !kotlin.hasProperty("targets")) {
                return
            }
            kotlin?.targets?.matching { target ->
                def platformType = target.hasProperty("platformType") ? target.platformType?.name : null
                platformType == "androidJvm" || platformType == "android"
            }?.each { target ->
                target.compilations?.matching { compilation ->
                    compilation.name == variantName ||
                        compilation.name == "${variantName}UnitTest" ||
                        compilation.name.equalsIgnoreCase("main") ||
                        compilation.name.equalsIgnoreCase("test")
                }?.each { compilation ->
                    compilation.allKotlinSourceSets?.each { sourceSet ->
                        addSourceDirectorySet(proj, roots, sourceSet.kotlin)
                        sourceSet.resources?.srcDirs?.each { dir ->
                            addRelativeDir(proj, roots, dir)
                        }
                    }
                    compilation.defaultSourceSet?.with { sourceSet ->
                        addSourceDirectorySet(proj, roots, sourceSet.kotlin)
                        sourceSet.resources?.srcDirs?.each { dir ->
                            addRelativeDir(proj, roots, dir)
                        }
                    }
                }
            }
        }

        def canonicalComponentKind = { value ->
            switch ((value ?: "").toString().toLowerCase()) {
                case "androidtest": return "androidTest"
                case "unittest": return "unitTest"
                case "screenshottest": return "screenshotTest"
                case "testfixtures": return "testFixtures"
                default: return value?.toString() ?: "test"
            }
        }

        def componentRecords = { variant ->
            def components = [[kind: "main", name: variant.name, component: variant]]
            if (variant.hasProperty("deviceTests")) {
                variant.deviceTests.each { name, component ->
                    components.add([kind: canonicalComponentKind(name),
                                    name: component.name, component: component])
                }
            } else if (variant.hasProperty("androidTest") && variant.androidTest != null) {
                components.add([kind: "androidTest", name: variant.androidTest.name,
                                component: variant.androidTest])
            }
            if (variant.hasProperty("hostTests")) {
                variant.hostTests.each { name, component ->
                    components.add([kind: canonicalComponentKind(name),
                                    name: component.name, component: component])
                }
            } else if (variant.hasProperty("unitTest") && variant.unitTest != null) {
                components.add([kind: "unitTest", name: variant.unitTest.name,
                                component: variant.unitTest])
            }
            if (variant.hasProperty("testFixtures") && variant.testFixtures != null) {
                components.add([kind: "testFixtures", name: variant.testFixtures.name,
                                component: variant.testFixtures])
            }
            components
        }

        def collectComponents = { proj, variant ->
            def allRoots = new LinkedHashSet<String>()
            def components = componentRecords(variant).collect { record ->
                def roots = new LinkedHashSet<String>()
                addComponentSourceRoots(proj, roots, record.component)
                if (record.kind == "main") {
                    addKotlinMultiplatformAndroidSourceRoots(proj, roots, variant.name)
                }
                allRoots.addAll(roots)
                [kind: record.kind, name: record.name, roots: roots]
            }
            [roots: new ArrayList(allRoots), components: components.collect { component ->
                new TargetModel.ComponentData(component.kind, component.name, new ArrayList(component.roots))
            }]
        }

        def proj = project

        androidPluginIds.each { pluginId ->
            proj.plugins.withId(pluginId) {
                def androidComponents = proj.extensions.findByName("androidComponents")
                if (androidComponents != null) {
                    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                        def applicationId = variant.hasProperty("applicationId") ? variant.applicationId.get() : ""
                        def testApplicationIdProvider =
                            variant.hasProperty("androidTest") && variant.androidTest != null
                                ? variant.androidTest.applicationId
                                : null
                        def buildType = variant.hasProperty("buildType") ? (variant.buildType ?: "") : ""
                        def productFlavors = variant.hasProperty("productFlavors")
                            ? variant.productFlavors.collect { flavor -> flavor.second }
                            : []
                        def android = proj.extensions.findByName("android")
                        def preferredBuildType = android?.buildTypes?.findByName(buildType)
                        def preferredBuildTypeFlag = preferredBuildType != null &&
                            preferredBuildType.hasProperty("isDefault") &&
                            preferredBuildType.isDefault == true
                        def preferredProductFlavors = android?.productFlavors?.findAll { flavor ->
                            flavor.hasProperty("isDefault") && flavor.isDefault == true
                        }?.collect { flavor -> flavor.name } ?: []
                        flavorRecords.add([
                            pluginId: pluginId,
                            projectType: pluginId.startsWith("com.android.")
                                ? pluginId.substring("com.android.".length())
                                    .replace("kotlin.multiplatform.library", "kmp-library")
                                : pluginId,
                            variantName: variant.name,
                            variant: variant,
                            applicationId: applicationId,
                            namespaceProvider: variant.hasProperty("namespace") ? variant.namespace : null,
                            testApplicationIdProvider: testApplicationIdProvider,
                            debuggable: variant.hasProperty("debuggable") ? variant.debuggable : false,
                            buildType: buildType,
                            productFlavors: productFlavors,
                            preferredBuildType: preferredBuildTypeFlag,
                            preferredProductFlavors: preferredProductFlavors
                        ])
                    }
                }
            }
        }

        registry.register(new Builder(proj, {
            flavorRecords.collect { record ->
                def components = collectComponents(proj, record.variant)
                new TargetModel.TargetData(proj.path, proj.projectDir.canonicalPath, proj.rootDir.canonicalPath,
                    record.variantName, record.applicationId, components.roots, record.buildType,
                    record.productFlavors, record.preferredBuildType, record.preferredProductFlavors,
                    record.pluginId, record.testApplicationIdProvider?.getOrNull() ?: "",
                    record.namespaceProvider?.getOrNull() ?: "", components.components, record.projectType,
                    record.debuggable)
            }
        }))
    }
    private static class Builder implements ToolingModelBuilder {
        private final Project owner
        private final Closure<List<TargetModel.Target>> collect
        Builder(Project owner, Closure<List<TargetModel.Target>> collect) {
            this.owner = owner; this.collect = collect
        }
        boolean canBuild(String name) { name == TargetModel.name }
        Object buildAll(String name, Project project) {
            if (project != owner) throw new IllegalArgumentException("Wrong project for target model")
            new Model(collect.call())
        }
    }
    private static class Model implements TargetModel, Serializable {
        final List<TargetModel.Target> targets
        Model(List<TargetModel.Target> targets) { this.targets = targets }
    }
}
