plugins { groovy }
repositories { mavenCentral() }
java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}
dependencies {
    compileOnly(gradleApi())
    compileOnly(localGroovy())
}
sourceSets.main { java.srcDir(rootProject.file("tooling-model/src/main/java")) }
tasks.jar { archiveFileName = "android-project-tooling.jar" }
