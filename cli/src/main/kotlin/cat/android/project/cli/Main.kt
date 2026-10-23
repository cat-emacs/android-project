package cat.android.project.cli

import cat.android.project.GradleTooling
import cat.android.project.ProjectFailure
import cat.android.project.synchronizeProject
import com.google.gson.*
import java.nio.file.*
import kotlin.system.exitProcess

private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
private fun envelope(vararg pairs: Pair<String,Any?>) = gson.toJsonTree(mapOf(*pairs)).asJsonObject
private class Arguments(raw: List<String>) {
    val json = "--json" in raw
    val command = raw.firstOrNull { it != "--json" } ?: "help"
    private val values = mutableMapOf<String,MutableList<String>>()
    init {
        val args=raw.filter { it != "--json" }
        var index=1
        while(index<args.size) {
            val token=args[index++]
            if (!token.startsWith("--")) throw ProjectFailure("Unexpected argument $token","input")
            val parts=token.removePrefix("--").split('=',limit=2)
            if (parts[0] !in setOf("project","task","gradle-arg","init-script","timeout","result-file"))
                throw ProjectFailure("Unknown option --${parts[0]}","input")
            val value=parts.getOrNull(1) ?: args.getOrNull(index++) ?: throw ProjectFailure("Missing $token value","input")
            values.getOrPut(parts[0]) { mutableListOf() }.add(value)
        }
    }
    fun get(key:String)=values[key]?.lastOrNull()
    fun all(key:String)=values[key].orEmpty()
    fun root()=Path.of(get("project") ?: ".").toAbsolutePath().normalize()
    fun timeout()=(get("timeout")?.toLongOrNull() ?: if(get("timeout")==null) 600 else throw ProjectFailure("Invalid timeout","input")).also {
        if(it<=0) throw ProjectFailure("Timeout must be positive","input")
    }
}
private fun write(path:Path,result:JsonObject) {
    Files.createDirectories(path.parent)
    val temporary=Files.createTempFile(path.parent,".android-project-",".json")
    try {
        Files.newBufferedWriter(temporary).use { gson.toJson(result,it) }
        try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch (_:AtomicMoveNotSupportedException) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}
fun main(raw:Array<String>) {
    if(raw.isEmpty() || raw.any { it=="--help" || it=="-h" }) {
        println("""android-project — independent Gradle/AGP project system (JDK 17+)
Usage: android-project <command> [--json] [options]
  doctor                                      Check backend capability (no Preview dependency)
  sync --project DIR [--timeout SECONDS]        Gradle build/module and Android target metadata
  build --project DIR --task :app:task          Cancellable Tooling API task execution
        [--init-script FILE] [--gradle-arg=ARG] [--timeout SECONDS]
Output: schemaVersion=1 JSON on stdout; progress/build output on stderr.
--result-file FILE writes the same object atomically.
No renderer, layoutlib, editor state, or Preview CLI is required.""")
        return
    }
    var args:Arguments?=null
    var code=0
    val result=try {
        args=Arguments(raw.toList())
        when(args.command) {
            "doctor" -> envelope("schemaVersion" to 1,"kind" to "android-project","status" to "ready",
                "javaVersion" to System.getProperty("java.version"),"gradleToolingApi" to "9.6.1","agpModelConsumer" to "9.3.0",
                "authRequired" to false,"capabilities" to mapOf("projectSync" to true,"taskExecution" to true,"dependencyResolution" to false))
            "sync" -> synchronizeProject(args.root(),args.timeout())
            "build" -> {
                val root=args.root()
                if(!Files.isRegularFile(root.resolve("gradle/wrapper/gradle-wrapper.properties"))) throw ProjectFailure("Project wrapper missing","input")
                val task=args.get("task") ?: throw ProjectFailure("Specify --task","input")
                val arguments=args.all("gradle-arg") + listOfNotNull(args.get("init-script")?.let { "--init-script" },args.get("init-script"))
                if (arguments.any { it == "--no-daemon" || it.startsWith("--no-daemon=") })
                    throw ProjectFailure("Tooling API requires daemon mode; use the Gradle wrapper for --no-daemon", "input")
                GradleTooling().use { it.build(root,task,arguments,emptyMap(),args.timeout()) }
                envelope("schemaVersion" to 1,"kind" to "android-project-build","status" to "ready","projectRoot" to root.toString(),"task" to task)
            }
            else -> throw ProjectFailure("Unknown command ${args.command}","input")
        }
    } catch(error:Exception) {
        code=2
        envelope("schemaVersion" to 1,"status" to "failed","error" to mapOf("kind" to ((error as? ProjectFailure)?.kind ?: "error"),"message" to error.message))
    }
    val emitted=try {
        args?.get("result-file")?.let { write(Path.of(it).toAbsolutePath().normalize(),result) }; result
    } catch(error:Exception) {
        code=2; envelope("schemaVersion" to 1,"status" to "failed","error" to mapOf("kind" to "output","message" to error.message))
    }
    println(if("--json" in raw) gson.toJson(emitted) else GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(emitted))
    exitProcess(code)
}
