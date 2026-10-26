package cat.android.project.cli

import cat.android.project.GradleTooling
import cat.android.project.ProjectFailure
import cat.android.project.synchronizeProject
import cat.android.project.synchronizeDependencies
import cat.android.project.io.readJsonObject
import cat.android.project.TargetSelection
import cat.android.project.io.atomicJson
import cat.android.project.io.jsonCodec as gson
import cat.android.project.io.jsonObject as envelope
import com.google.gson.*
import java.nio.file.*
import kotlin.system.exitProcess

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
            if (parts[0] !in setOf("project","task","gradle-arg","init-script","timeout","result-file","selection-file","dependencies","metadata-file"))
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
fun main(raw:Array<String>) {
    if(raw.isEmpty() || raw.any { it=="--help" || it=="-h" }) {
        println("""android-project — independent Gradle/AGP project system (JDK 17+)
Usage: android-project <command> [--json] [options]
  doctor                                      Check backend capability (no Preview dependency)
  sync --project DIR [--timeout SECONDS]        Gradle build/module and Android target metadata
       [--dependencies none|requested|selected] Default none; requested uses exact selection-file targets
       [--selection-file JSON]                 [{buildRoot,modulePath,variant}] dependency selections
  dependencies --project DIR --metadata-file JSON --selection-file JSON
                                               Exact graphs against a fresh metadata context
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
            "sync", "dependencies" -> {
                val selections=args.get("selection-file")?.let { file ->
                    val value=Files.newBufferedReader(Path.of(file)).use { JsonParser.parseReader(it) }
                    if(!value.isJsonArray) throw ProjectFailure("Selection file must be an array","input")
                    value.asJsonArray.map { item ->
                        if(!item.isJsonObject) throw ProjectFailure("Invalid target selection","input")
                        fun field(key:String):String {
                            val field=item.asJsonObject[key]
                            if(field == null || !field.isJsonPrimitive || !field.asJsonPrimitive.isString || field.asString.isBlank())
                                throw ProjectFailure("Invalid selection $key","input")
                            return field.asString
                        }
                        TargetSelection(Path.of(field("buildRoot")).toFile().canonicalPath,field("modulePath"),field("variant"))
                    }
                } ?: emptyList()
                if (args.command == "dependencies") {
                    val file=args.get("metadata-file") ?: throw ProjectFailure("Specify --metadata-file", "input")
                    synchronizeDependencies(args.root(),readJsonObject(Path.of(file)),selections,args.timeout())
                } else synchronizeProject(args.root(),args.timeout(),selections,args.get("dependencies") ?: "none")
            }
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
        args?.get("result-file")?.let { atomicJson(Path.of(it).toAbsolutePath().normalize(),result) }; result
    } catch(error:Exception) {
        code=2; envelope("schemaVersion" to 1,"status" to "failed","error" to mapOf("kind" to "output","message" to error.message))
    }
    println(if("--json" in raw) gson.toJson(emitted) else GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(emitted))
    exitProcess(code)
}
