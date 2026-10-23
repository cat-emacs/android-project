plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
}
group = "org.cat-emacs"
version = "0.1.3"
base { archivesName = "android-project-core" }
repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
}
kotlin { jvmToolchain(17) }
dependencies {
    api("com.google.code.gson:gson:2.13.2")
    implementation("org.gradle:gradle-tooling-api:9.6.1")
    implementation("com.android.tools.build:builder-model:9.3.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets.main { java.srcDir(rootProject.file("tooling-model/src/main/java")) }
tasks.processResources {
    dependsOn(":tooling:jar")
    from(rootProject.file("gradle/project.init.gradle")) { into("cat/android/project") }
    from(project(":tooling").tasks.named("jar")) { into("cat/android/project") }
}
tasks.test {
    useJUnitPlatform()
    systemProperty("project.test.gradle.home", gradle.gradleHomeDir!!.absolutePath)
}
java { withSourcesJar() }
publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
            artifactId = "android-project-core"
            pom {
                name = "Android Project Core"
                description = "Independent Gradle Tooling API and Android project model backend"
                url = "https://github.com/cat-emacs/android-project"
                licenses {
                    license {
                        name = "GNU General Public License v3.0 or later"
                        url = "https://www.gnu.org/licenses/gpl-3.0.html"
                        distribution = "repo"
                    }
                }
                scm {
                    url = "https://github.com/cat-emacs/android-project"
                    connection = "scm:git:https://github.com/cat-emacs/android-project.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/cat-emacs/android-project")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
        maven {
            name = "projectCore"
            url = uri(providers.gradleProperty("androidProjectRepository")
                .orElse(rootProject.layout.buildDirectory.dir("project-core-repository").get().asFile.absolutePath).get())
        }
    }
}
