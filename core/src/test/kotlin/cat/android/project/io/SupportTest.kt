package cat.android.project.io

import cat.android.project.ProjectFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SupportTest {
    @TempDir lateinit var root: Path

    @Test fun `canonical JSON preserves existing hashes nulls and array order`() {
        val first = jsonObject("z" to null, "a" to listOf(1, 2), "nested" to mapOf("b" to false, "a" to "<value>"))
        val second = jsonObject("nested" to mapOf("a" to "<value>", "b" to false), "a" to listOf(1, 2), "z" to null)
        assertEquals(jsonHash(first), jsonHash(second))
        assertNotEquals(jsonHash(first), jsonHash(jsonObject("a" to listOf(2, 1), "z" to null, "nested" to first["nested"])))
        assertEquals("{\"a\":[1,2],\"nested\":{\"a\":\"<value>\",\"b\":false},\"z\":null}", jsonCodec.toJson(canonicalJson(first)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".toByteArray()))
    }

    @Test fun `JSON field checks distinguish absent null false and malformed types`() {
        val value = jsonObject("name" to "ok", "empty" to null, "flag" to false, "names" to listOf("a", "b"), "mixed" to listOf("a", 1))
        assertEquals("ok", value.jsonText("name"))
        assertNull(value.jsonText("missing"))
        assertNull(value.jsonText("empty"))
        assertTrue(value.jsonArray("missing").isEmpty)
        assertTrue(value.jsonArray("empty").isEmpty)
        assertEquals(listOf("a", "b"), value.jsonStrings("names"))
        assertEquals("protocol", assertFailsWith<ProjectFailure> { value.jsonText("flag") }.kind)
        assertFailsWith<ProjectFailure> { value.jsonArray("name") }
        assertFailsWith<ProjectFailure> { value.jsonStrings("mixed") }
        val path = root.resolve("array.json")
        path.writeText("[]")
        assertFailsWith<ProjectFailure> { readJsonObject(path) }
    }

    @Test fun `atomic writes replace files and leave no staging file after failure`() {
        val path = root.resolve("nested/model.json")
        atomicJson(path, jsonObject("value" to "original"))
        assertEquals("original", readJsonObject(path).jsonText("value"))
        assertFailsWith<IllegalStateException> {
            atomicWrite(path) { it.write("partial"); error("writer failed") }
        }
        assertEquals("original", readJsonObject(path).jsonText("value"))
        atomicJson(path, jsonObject("value" to "new", "null" to null))
        assertEquals("new", readJsonObject(path).jsonText("value"))
        assertEquals(listOf(path), walkFiles(root))
    }

    @Test fun `failed replacement cleans staging file without deleting destination`() {
        val path = root.resolve("destination")
        path.createDirectories()
        path.resolve("keep").writeText("kept")
        assertFailsWith<java.io.IOException> { atomicJson(path, jsonObject("value" to 1)) }
        assertEquals(listOf(path.resolve("keep")), walkFiles(root))
    }

    @Test fun `traversal is sorted and exclusions belong to caller`() {
        root.resolve("build").createDirectories()
        root.resolve("build/out.kt").writeText("output")
        root.resolve("z.kt").writeText("z")
        root.resolve("a.kt").writeText("a")
        assertEquals(listOf("a.kt", "build/out.kt", "z.kt"), walkFiles(root).map { root.relativize(it).toString() })
        assertEquals(listOf("a.kt", "z.kt"), walkFiles(root) { it.fileName.toString() == "build" }.map { it.fileName.toString() })
        assertEquals(3, walkFiles(root) { it == root }.size)
        assertTrue(walkFiles(root.resolve("absent")).isEmpty())
    }

    @Test fun `file stamps and content hashes detect mutation and missing files`() {
        val file = root.resolve("source.kt")
        assertNull(fileStamp(file))
        file.writeText("old")
        val stamp = fileStamp(file)
        val content = contentHash(file)
        val tree = contentHash(root)
        file.writeText("different content")
        assertNotEquals(stamp, fileStamp(file))
        assertNotEquals(content, contentHash(file))
        assertNotEquals(tree, contentHash(root))
        file.deleteExisting()
        assertNull(fileStamp(file))
    }

    @Test fun `directory links are not followed but file target and link identity are preserved`() {
        val outside = Files.createTempDirectory(root.parent, "core-link-target-")
        try {
            outside.resolve("source.kt").writeText("target")
            val directory = root.resolve("linked-dir")
            val file = root.resolve("linked-file")
            Files.createSymbolicLink(directory, outside)
            Files.createSymbolicLink(file, outside.resolve("source.kt"))
            assertEquals(listOf(directory, file), walkFiles(root))
            assertTrue(walkFiles(directory).isEmpty())
            assertEquals(fileStamp(outside.resolve("source.kt")), fileStamp(file))
            assertEquals(outside.resolve("source.kt").toString(), symbolicLinkTarget(file))
            assertNull(symbolicLinkTarget(outside.resolve("source.kt")))
        } finally {
            outside.resolve("source.kt").deleteExisting()
            outside.deleteExisting()
        }
    }
}
