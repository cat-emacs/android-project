package cat.android.project.io

import cat.android.project.ProjectFailure
import com.google.gson.*
import java.nio.file.Files
import java.nio.file.Path

/** Shared wire encoding; CLI envelopes and schema validation stay with consumers. */
val jsonCodec: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

fun jsonObject(vararg fields: Pair<String, Any?>): JsonObject = jsonCodec.toJsonTree(mapOf(*fields)).asJsonObject

fun JsonObject.jsonText(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString)
        throw ProjectFailure("Expected string: $key", "protocol")
    it.asString
}

fun JsonObject.jsonArray(key: String): JsonArray {
    val value = get(key)?.takeUnless { it.isJsonNull } ?: return JsonArray()
    if (!value.isJsonArray) throw ProjectFailure("Expected array: $key", "protocol")
    return value.asJsonArray
}

fun JsonObject.jsonStrings(key: String): List<String> = jsonArray(key).map {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString)
        throw ProjectFailure("Expected string array: $key", "protocol")
    it.asString
}

fun readJsonObject(path: Path): JsonObject = Files.newBufferedReader(path).use {
    val value = JsonParser.parseReader(it)
    if (!value.isJsonObject) throw ProjectFailure("Expected JSON object: $path", "protocol")
    value.asJsonObject
}

/** Stable object ordering, preserving array order and explicit nulls. */
fun canonicalJson(value: JsonElement): JsonElement = when {
    value.isJsonObject -> JsonObject().also { output ->
        value.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, item) -> output.add(key, canonicalJson(item)) }
    }
    value.isJsonArray -> JsonArray().also { output -> value.asJsonArray.forEach { output.add(canonicalJson(it)) } }
    else -> value
}

fun jsonHash(value: Any?): String = sha256(jsonCodec.toJson(canonicalJson(jsonCodec.toJsonTree(value))).toByteArray(Charsets.UTF_8))

fun atomicJson(path: Path, value: JsonElement) {
    atomicWrite(path) { writer -> jsonCodec.toJson(value, writer) }
}
