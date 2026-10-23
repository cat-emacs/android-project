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
application {
    mainClass = "cat.android.project.cli.MainKt"
    applicationName = "android-project"
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
