package cat.android.project

import cat.android.project.io.fileStamp
import cat.android.project.io.sha256
import java.nio.file.Path
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*

/** Requested JVM policy; only the build JVM can report which runtime actually ran. */
data class GradleJvmSelection(val source: String, val javaHome: String?, val criteriaFile: String? = null) {
    fun arguments(): List<String> = if (source == "explicit") listOf("-Dorg.gradle.java.home=$javaHome") else emptyList()
}

private fun properties(path: Path): Properties = Properties().apply {
    if (path.isRegularFile()) path.inputStream().use { load(it) }
}

/** Native Gradle policy wins over IDE fallbacks; criteria also wins over an explicit home. */
fun selectGradleJvm(root: Path, explicitHome: Path? = null, env: Map<String,String> = System.getenv()): GradleJvmSelection {
    val project = root.toRealPath()
    fun home(value: String, source: String): GradleJvmSelection {
        val path = Path.of(value)
        if (!path.isAbsolute || !path.resolve("release").isRegularFile() ||
            !path.resolve("bin").resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").isRegularFile())
            throw ProjectFailure("Invalid Gradle JDK from $source: $value", "input")
        return GradleJvmSelection(source,path.toRealPath().toString())
    }
    // Validate explicit input even when Gradle criteria will take precedence.
    val explicit = explicitHome?.let { home(it.toString(),"explicit") }
    val criteria = project.resolve("gradle/gradle-daemon-jvm.properties")
    val distribution = properties(project.resolve("gradle/wrapper/gradle-wrapper.properties")).getProperty("distributionUrl", "")
    val version = Regex("gradle-(\\d+)\\.(\\d+)").find(distribution)?.groupValues
    val supportsCriteria = version == null || version[1].toInt() > 8 ||
        (version[1].toInt() == 8 && version[2].toInt() >= 8)
    if (supportsCriteria && criteria.isRegularFile() && properties(criteria).getProperty("toolchainVersion") != null)
        return GradleJvmSelection("daemon-jvm-criteria",null,criteria.toString())
    if (explicit != null) return explicit
    val userHome = Path.of(env["GRADLE_USER_HOME"] ?: Path.of(System.getProperty("user.home"),".gradle").toString())
    for ((file,source) in listOf(userHome.resolve("gradle.properties") to "user-gradle.properties",
            project.resolve("gradle.properties") to "project-gradle.properties")) {
        properties(file).getProperty("org.gradle.java.home")?.let { return home(it,source) }
    }
    val idea = project.resolve(".idea/gradle.xml")
    if (idea.isRegularFile()) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
        }
        val document = try { factory.newDocumentBuilder().parse(idea.toFile()) }
            catch (error: Exception) { throw ProjectFailure("Cannot read $idea: ${error.message}","input") }
        val settings = document.getElementsByTagName("GradleProjectSettings")
        for (i in 0 until settings.length) {
            val options = (settings.item(i) as org.w3c.dom.Element).getElementsByTagName("option")
            val values = (0 until options.length).map { options.item(it) as org.w3c.dom.Element }
                .associate { it.getAttribute("name") to it.getAttribute("value") }
            val linked = values["externalProjectPath"]?.replace("\$PROJECT_DIR\$",project.toString()) ?: continue
            if (Path.of(linked).toFile().canonicalPath != project.toString()) continue
            when (val name = values["gradleJvm"]) {
                null -> Unit
                "#JAVA_HOME", "JAVA_HOME" -> return home(env["JAVA_HOME"]
                    ?: throw ProjectFailure("IDE Gradle JDK selects JAVA_HOME but it is unset; select a JDK explicitly", "input"), "JAVA_HOME")
                "#GRADLE_LOCAL_JAVA_HOME", "GRADLE_LOCAL_JAVA_HOME" -> {
                    val local = project.resolve(".gradle/config.properties")
                    val value = properties(local).getProperty("java.home")
                        ?: throw ProjectFailure("GRADLE_LOCAL_JAVA_HOME has no java.home in $local; select a JDK explicitly", "input")
                    return home(value,"GRADLE_LOCAL_JAVA_HOME")
                }
                else -> {
                    if (Path.of(name).isAbsolute) return home(name,"idea-gradle-jvm")
                    throw ProjectFailure("IDE Gradle JDK '$name' requires the IDE SDK table; select a JDK explicitly", "unsupported")
                }
            }
        }
    }
    return home(env["JAVA_HOME"] ?: System.getProperty("java.home"),
        if (env["JAVA_HOME"] != null) "JAVA_HOME" else "cli-runtime")
}

/** Covers ignored IDE settings and user-level Gradle inputs as well as selected JDK replacement. */
fun gradleJvmSignature(root: Path, explicitHome: Path? = null, env: Map<String,String> = System.getenv()): String {
    val project = root.toRealPath()
    val selection = selectGradleJvm(project,explicitHome,env)
    val userHome = Path.of(env["GRADLE_USER_HOME"] ?: Path.of(System.getProperty("user.home"),".gradle").toString())
    val files = listOf(project.resolve(".idea/gradle.xml"),project.resolve(".gradle/config.properties"),
        project.resolve("gradle/gradle-daemon-jvm.properties"),project.resolve("gradle.properties"),userHome.resolve("gradle.properties")) +
        listOfNotNull(selection.javaHome?.let { Path.of(it,"release") },selection.javaHome?.let {
            Path.of(it,"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java") })
    val records = files.map { listOf(it.toAbsolutePath().normalize().toString(),fileStamp(it)) }
    return sha256((selection.toString() + records.toString() +
        listOf("JAVA_HOME","GRADLE_USER_HOME","GRADLE_OPTS","JAVA_OPTS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS")
            .associateWith { env[it] }.toString()).toByteArray())
}
