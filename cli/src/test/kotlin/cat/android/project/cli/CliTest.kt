package cat.android.project.cli

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CliTest {
    @TempDir lateinit var directory: Path
    private fun run(vararg arguments: String): Pair<Int,String> {
        val process=ProcessBuilder(listOf(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",
            System.getProperty("project.test.classpath"),"cat.android.project.cli.MainKt") + arguments)
            .directory(directory.toFile()).redirectError(directory.resolve("stderr.log").toFile()).start()
        val output=process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
    @Test fun `doctor is independent of Preview and cwd`() {
        val (status,output)=run("doctor","--json")
        assertEquals(0,status)
        val result=JsonParser.parseString(output).asJsonObject
        assertEquals("android-project",result["kind"].asString)
        assertTrue(result["capabilities"].asJsonObject["projectSync"].asBoolean)
        assertFalse(System.getProperty("project.test.classpath").contains("compose-preview"))
    }
    @Test fun `invalid root writes same structured error to atomic result file`() {
        val path=directory.resolve("result.json")
        val (status,output)=run("sync","--project",directory.toString(),"--result-file",path.toString(),"--json")
        assertEquals(2,status)
        assertEquals(JsonParser.parseString(output),JsonParser.parseString(path.readText()))
        assertEquals("input",JsonParser.parseString(output).asJsonObject["error"].asJsonObject["kind"].asString)
    }
    @Test fun `no daemon cannot be smuggled through Gradle arguments`() {
        directory.resolve("gradle/wrapper").createDirectories()
        directory.resolve("gradle/wrapper/gradle-wrapper.properties").writeText("unused fixture")
        val (status,output)=run("build","--project",directory.toString(),"--task",":probe","--gradle-arg=--no-daemon","--json")
        assertEquals(2,status)
        val error=JsonParser.parseString(output).asJsonObject["error"].asJsonObject
        assertEquals("input",error["kind"].asString)
        assertTrue(error["message"].asString.contains("requires daemon"))
    }
    @Test fun `malformed variant selection cannot start model resolution`() {
        val file=directory.resolve("selection.json")
        for(value in listOf("{}","[false]","[{\"buildRoot\":\"/root\",\"modulePath\":false,\"variant\":\"debug\"}]")) {
            file.writeText(value)
            val (status,output)=run("sync","--selection-file",file.toString(),"--json")
            assertEquals(2,status)
            assertEquals("input",JsonParser.parseString(output).asJsonObject["error"].asJsonObject["kind"].asString)
        }
    }
    @Test fun `dependency sync rejects missing or malformed baseline before connecting`() {
        val (status,output)=run("dependencies","--json")
        assertEquals(2,status)
        assertEquals("input",JsonParser.parseString(output).asJsonObject["error"].asJsonObject["kind"].asString)
        val file=directory.resolve("metadata.json")
        file.writeText("{}")
        val (badStatus,badOutput)=run("dependencies","--metadata-file",file.toString(),"--json")
        assertEquals(2,badStatus)
        assertEquals("stale-baseline",JsonParser.parseString(badOutput).asJsonObject["error"].asJsonObject["kind"].asString)
    }
    @Test fun `malformed options and unsupported no daemon are rejected`() {
        for(args in listOf(arrayOf("sync","--no-daemon","--json"),arrayOf("build","--timeout","-1","--json"),arrayOf("unknown","--json"))) {
            val (status,output)=run(*args)
            assertEquals(2,status)
            assertEquals("failed",JsonParser.parseString(output).asJsonObject["status"].asString)
        }
    }
}
