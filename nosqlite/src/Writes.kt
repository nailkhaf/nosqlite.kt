package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal data class Written(val rowId: Long, val json: String)

/** A tracked collection has no where-write; those are [Collection] extensions and do not resolve for it. */
internal fun BaseCollection.asBulk(): Collection = this as? Collection
    ?: throw IllegalArgumentException(
        "Tracked collection '$name' has no bulk write; update or delete one document by its primary key or a unique index",
    )

context(_: WriteScope)
internal suspend fun BaseCollection.insertDocument(json: String) {
    val stored = withConnection { Queries.insert(this@insertDocument).execute(json) }
    val document = parseDocument(stored.json)
    enforcePrimaryKey(before = null, after = document)
    changed()
    interceptor?.insert(document)
}

context(_: WriteScope)
internal suspend fun BaseCollection.updateWhere(patch: String, condition: Where): Int =
    modifyWhere(condition) { Queries.update(this, patch, condition).execute() }

context(_: WriteScope)
internal suspend fun BaseCollection.replaceWhere(json: String, condition: Where): Int =
    modifyWhere(condition) { Queries.replace(this, json, condition).execute() }

context(_: WriteScope)
internal suspend fun BaseCollection.deleteWhere(condition: Where): Int {
    val deleted = withConnection { Queries.delete(this@deleteWhere, condition).execute() }
    if (deleted.isEmpty()) return 0
    changed()
    interceptor?.let { hook -> deleted.forEach { hook.delete(parseDocument(it.json)) } }
    return deleted.size
}

context(_: WriteScope)
private suspend fun BaseCollection.modifyWhere(
    condition: Where,
    write: suspend context(Connection) () -> List<Written>,
): Int {
    val before = withConnection { Queries.selectRows(this@modifyWhere, condition).execute() }
    if (before.isEmpty()) return 0
    val after = withConnection { write() }
    val previous = before.associateBy { it.rowId }
    val pairs = after.map { row ->
        val old = previous[row.rowId]
        (old?.let { parseDocument(it.json) }) to parseDocument(row.json)
    }
    pairs.forEach { (old, new) -> enforcePrimaryKey(old, new) }
    if (pairs.isNotEmpty()) changed()
    interceptor?.let { hook ->
        pairs.forEach { (old, new) ->
            if (old == null) hook.insert(new) else hook.update(old, new)
        }
    }
    return pairs.size
}

internal fun BaseCollection.enforcePrimaryKey(before: JsonObject?, after: JsonObject) {
    val field = id?.fields?.single() ?: return
    val now = after.fieldAt(field)
    if (now == null || now is JsonNull || now !is JsonPrimitive) {
        throw NoSqliteException.PrimaryKeyChanged(name, field, missing = true)
    }
    if (before != null && before.fieldAt(field) != now) {
        throw NoSqliteException.PrimaryKeyChanged(name, field, missing = false)
    }
}

internal fun parseDocument(json: String): JsonObject {
    val element = NoSqlite.Json.parseToJsonElement(json)
    check(element is JsonObject) { "Stored document is not a JSON object: $json" }
    return element
}

internal fun JsonObject.fieldAt(field: String): JsonElement? {
    var current: JsonElement = this
    for (key in field.split('.')) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current
}

internal fun JsonPrimitive.toSqlValue(): SqlValue = when {
    this is JsonNull -> SqlValue.Null
    isString -> SqlValue.Text(content)
    booleanOrNull != null -> SqlValue.Integer(if (booleanOrNull == true) 1 else 0)
    longOrNull != null && '.' !in content && 'e' !in content && 'E' !in content -> SqlValue.Integer(longOrNull!!)
    doubleOrNull != null -> SqlValue.Real(doubleOrNull!!)
    else -> SqlValue.Text(content)
}

/** The condition a unique [index] conflicts on for [document], or null when a key is null or missing. */
internal fun UniqueIndex.matches(document: JsonObject): Where? {
    val keys = paths.map { path ->
        val value = document.fieldAt(path.field)
        if (value !is JsonPrimitive || value is JsonNull) return null
        Where.Compare(path, Comparison.Eq, value.toSqlValue())
    }
    return (keys + listOfNotNull(where)).reduce { left, right -> Where.And(left, right) }
}
