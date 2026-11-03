package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.Collection
import io.github.nailkhaf.nosqlite.UniqueIndex
import io.github.nailkhaf.nosqlite.Where
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.insert
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A [Collection] that records each write in [PendingChanges]. [id] is its primary key: one unique index, and the
 * field does not change. Declare [PendingChanges] in the same schema.
 * ```
 * object Contacts : TrackedCollection("contacts") {
 *     override val id = unique(Contact::id)
 *     val byEmail = unique(Contact::email)
 * }
 * ```
 * Writes are the collection's own: `id.upsert(value)`, `byEmail(email).delete()`. Pass [clock] when timestamps
 * should not follow [Clock.System].
 */
public abstract class TrackedCollection(
    name: String,
    private val clock: Clock = Clock.System,
) : Collection(name) {
    /** The primary key. One field, and writes do not change it. */
    public abstract val id: UniqueIndex

    context(_: WriteScope)
    protected override suspend fun insert(json: String) {
        val document = json.objectValue()
        require(document.at(fieldOf(id)) != null) { "Document of '$name' is missing primary key ${fieldOf(id)}" }
        super.insert(json)
        record(PendingChange.Action.Insert, document)
    }

    context(_: WriteScope)
    protected override suspend fun upsert(index: UniqueIndex, json: String) {
        val key = upsertReturning(index, json, id)
        record(PendingChange.Action.Upsert, json.objectValue().withKey(key))
    }

    context(_: WriteScope)
    protected override suspend fun update(json: String, condition: Where): Boolean {
        val key = updateReturning(json, condition, id) ?: return false
        record(PendingChange.Action.Update, json.objectValue().withKey(key))
        return true
    }

    context(_: WriteScope)
    protected override suspend fun replace(json: String, condition: Where): Boolean {
        val key = replaceReturning(json, condition, id) ?: return false
        record(PendingChange.Action.Replace, json.objectValue().withKey(key))
        return true
    }

    context(_: WriteScope)
    protected override suspend fun delete(condition: Where): Boolean {
        val key = deleteReturning(condition, id) ?: return false
        record(PendingChange.Action.Delete, key.only())
        return true
    }

    private fun PrimaryKey.element(): JsonElement = io.github.nailkhaf.nosqlite.NoSqlite.Json.parseToJsonElement(json)

    private fun PrimaryKey.only(): JsonObject {
        var element = element()
        for (part in field.split('.').asReversed()) element = JsonObject(mapOf(part to element))
        return element as JsonObject
    }

    context(_: WriteScope)
    private suspend fun record(action: PendingChange.Action, changeSet: JsonObject) {
        if (isUntracked()) return
        PendingChanges.insert(
            PendingChange(
                id = Uuid.generateV7(),
                collection = name,
                action = action,
                changeSet = changeSet,
                timestamp = clock.now(),
            ),
        )
    }

    private fun JsonObject.withKey(key: PrimaryKey): JsonObject = withKey(key.field.split('.'), key.element())

    private fun JsonObject.withKey(path: List<String>, key: JsonElement): JsonObject {
        val head = path.first()
        if (path.size == 1) {
            if (head in this && this[head] !is JsonNull) return this
            return JsonObject(this + (head to key))
        }
        val child = this[head] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(this + (head to child.withKey(path.drop(1), key)))
    }
}

private fun String.objectValue(): JsonObject {
    val element = io.github.nailkhaf.nosqlite.NoSqlite.Json.parseToJsonElement(this)
    require(element is JsonObject) { "A document must be a JSON object, not $this" }
    return element
}

private fun JsonObject.at(field: String): JsonElement? {
    var current: JsonElement = this
    for (part in field.split('.')) {
        val child = (current as? JsonObject)?.get(part) ?: return null
        if (child is JsonNull) return null
        current = child
    }
    return current
}

