package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One recorded insert, update or delete of a tracked collection. An update that changes nothing isn't recorded. */
public data class PendingChange(
    /** 16 random bytes, fixed when recorded, so a retried upload can reuse it as a remote id. Not a v4 UUID. */
    public val id: Uuid,
    /** The collection's name. */
    public val collection: String,
    /** The document before the change; `null` for an insert. */
    public val before: JsonObject?,
    /** The document after the change; `null` for a delete. */
    public val after: JsonObject?,
    /** When it was recorded, by SQLite's clock, in milliseconds. */
    public val timestamp: Instant,
)

/** The document's `id`, taken from [PendingChange.after], or from [PendingChange.before] when that is null. */
public val PendingChange.documentId: Uuid
    get() {
        val document = after ?: before ?: error("A pending change has no document")
        val id = document["id"]
        require(id is JsonPrimitive && id.isString) { "A pending change document has no string id" }
        return Uuid.parse(id.content)
    }

/** The pending changes recorded so far, in the order they were recorded. */
context(_: ReadScope)
public suspend fun pendingChanges(): List<PendingChange> = context(connection()) { Queries.pendingChanges.execute() }

/** Deletes exactly these rows. A change written after they were read stays. */
context(_: WriteScope)
public suspend fun removePendingChanges(changes: List<PendingChange>) {
    if (changes.isEmpty()) return
    context(connection()) { Queries.deletePendingChanges.execute(changes.map { it.id }) }
}
