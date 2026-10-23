plugins {
    kotlin("jvm") version "2.3.0"
    application
}
repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("project.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
version = providers.gradleProperty("cliVersion").get()

application {
    mainClass = "cat.android.project.cli.MainKt"
    applicationName = "android-project"
}
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

distributions {
    main {
        distributionBaseName = "android-project"
        contents {
            from(rootProject.file("android-project.el")) { into("lisp") }
            from(rootProject.file("LICENSE"))
        }
    }
}
