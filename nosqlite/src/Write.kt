package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * How a typed write changes a stored document. A class owns the fields it declares and nothing else, so it [Merge]s:
 * any other document, such as a [JsonObject], a map or a sealed class, [Replace]s the stored one.
 */
internal sealed interface Write {
    class Replace(val json: String) : Write

    /**
     * [patch] is the value with explicit nulls, applied as a JSON merge patch: a declared field is set, a null one
     * removed, and an undeclared one kept, in nested objects too. A patch can't tell a removed map key or a changed
     * subtype, so fields of a map, polymorphic or contextual type, by their JSON paths, are [removed] first.
     */
    class Merge(val removed: List<String>, val patch: String) : Write
}

/** The new document as SQL, in a statement where `data` is the stored one. */
internal val Write.sql: Fragment
    get() = when (this) {
        is Write.Replace -> Fragment("jsonb(?)", listOf(SqlValue.Text(json)))
        is Write.Merge -> {
            val stored =
                if (removed.isEmpty()) "data" else removed.joinToString(", ", "jsonb_remove(data, ", ")") { "?" }
            Fragment("jsonb_patch($stored, ?)", (removed + patch).map(SqlValue::Text))
        }
    }

internal fun <T> Collection.encode(serializer: KSerializer<T>, value: T): String {
    val json = NoSqlite.Json.encodeToString(serializer, value)
    require(json.startsWith('{')) { "A document of '$name' must be a JSON object, not $json" }
    return json
}

internal fun <T> Collection.writeOf(serializer: KSerializer<T>, value: T): Write {
    val descriptor = serializer.descriptor.unwrapped
    if (!descriptor.isClass) return Write.Replace(encode(serializer, value))
    val patch = ExplicitNulls.encodeToJsonElement(serializer, value)
    require(patch is JsonObject) { "A document of '$name' must be a JSON object, not $patch" }
    return Write.Merge(replacedPaths(descriptor, patch, "$"), patch.toString())
}

// Walks the value along with its descriptor, so a recursive class ends where its value does.
private fun replacedPaths(descriptor: SerialDescriptor, value: JsonObject, prefix: String): List<String> =
    (0 until descriptor.elementsCount).flatMap { index ->
        val key = descriptor.getElementName(index)
        val field = value[key] ?: return@flatMap emptyList()
        val element = descriptor.getElementDescriptor(index).unwrapped
        require('"' !in key) { "Field '$key' of ${descriptor.serialName} can't be updated: its name has a '\"'" }
        val path = "$prefix.\"$key\""
        when {
            element.isClass -> if (field is JsonObject) replacedPaths(element, field, path) else emptyList()
            element.isOpaque -> listOf(path)
            else -> emptyList()
        }
    }

private val ExplicitNulls = Json(NoSqlite.Json) { explicitNulls = true }

private val SerialDescriptor.unwrapped: SerialDescriptor
    get() = if (isInline) getElementDescriptor(0).unwrapped else this

private val SerialDescriptor.isClass: Boolean
    get() = kind == StructureKind.CLASS || kind == StructureKind.OBJECT

private val SerialDescriptor.isOpaque: Boolean
    get() = kind == StructureKind.MAP || kind is PolymorphicKind || kind == SerialKind.CONTEXTUAL
