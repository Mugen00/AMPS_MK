package dev.amps.app.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lenient accessors for the public JSON APIs.
 *
 * AniList occasionally answers a field with a different scalar type than its
 * documentation suggests (`age` is documented as a string but has been served
 * as a number before), and the music sources are community driven. Reading
 * everything through these helpers keeps one odd field from breaking a whole
 * wiki page.
 */
fun JsonObject.at(vararg path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}

fun JsonObject.str(vararg path: String): String? =
    (at(*path) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.int(vararg path: String): Int? = str(*path)?.toIntOrNull()

fun JsonObject.long(vararg path: String): Long? = str(*path)?.toLongOrNull()

fun JsonObject.dbl(vararg path: String): Double? = str(*path)?.toDoubleOrNull()

fun JsonObject.bool(vararg path: String): Boolean? = str(*path)?.toBooleanStrictOrNull()

fun JsonObject.arr(vararg path: String): JsonArray =
    at(*path) as? JsonArray ?: JsonArray(emptyList())

fun JsonObject.obj(vararg path: String): JsonObject? = at(*path) as? JsonObject

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

fun JsonObject.strings(vararg path: String): List<String> = arr(*path).strings()
