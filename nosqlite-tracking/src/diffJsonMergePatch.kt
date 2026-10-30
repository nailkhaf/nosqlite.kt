package io.github.nailkhaf.nosqlite.tracking

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** The RFC 7396 merge patch that turns [target] into [updated]: empty when they are equal. */
internal fun diffJsonMergePatch(target: JsonObject, updated: JsonObject): JsonObject = buildJsonObject {
    for ((key, updatedValue) in updated) {
        when (val targetValue = target[key]) {
            null -> put(key, updatedValue)

            updatedValue -> continue

            is JsonObject if updatedValue is JsonObject -> diffJsonMergePatch(targetValue, updatedValue)
                .takeIf { it.isNotEmpty() }
                ?.let { put(key, it) }

            else -> put(key, updatedValue)
        }
    }

    for (key in target.keys) {
        if (key !in updated) put(key, JsonNull)
    }
}

/** [extra] merged into this object. Objects along the same path merge, and [extra] wins at a leaf. */
internal fun JsonObject.withFields(extra: JsonObject): JsonObject = buildJsonObject {
    for (key in keys + extra.keys) {
        val here = this@withFields[key]
        val there = extra[key]
        val value = when {
            here is JsonObject && there is JsonObject -> here.withFields(there)
            there != null -> there
            else -> here!!
        }
        put(key, value)
    }
}

/** [field] of this document, as its own object: `"id"` or a nested `"meta.id"`. */
internal fun JsonObject.primaryKeyObject(field: String): JsonObject {
    val keys = field.split('.')
    val value: JsonElement = keys.fold(this as JsonElement) { element, key ->
        (element as? JsonObject)?.get(key) ?: JsonNull
    }
    return keys.dropLast(1).foldRight(JsonObject(mapOf(keys.last() to value))) { key, child ->
        JsonObject(mapOf(key to child))
    }
}
