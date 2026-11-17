package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
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

/**
 * The pending changes recorded so far, in the order they were recorded: at most [limit], after skipping [offset].
 * [limit] must be positive and [offset] not negative.
 */
context(_: ReadScope)
public suspend fun pendingChanges(limit: Int = Int.MAX_VALUE, offset: Int = 0): List<PendingChange> {
    require(limit > 0) { "Limit must be positive: $limit" }
    require(offset >= 0) { "Offset must not be negative: $offset" }
    return context(connection()) { Queries.pendingChanges.execute(limit to offset) }
}

/**
 * [pendingChanges] with [limit] and [offset], now and after every committed write that changed a tracked collection
 * or removed pending changes. Writes that land while a read runs are folded into the next one, and a read that finds
 * what the previous one found emits nothing.
 */
context(db: NoSqlite)
public fun observePendingChanges(limit: Int = Int.MAX_VALUE, offset: Int = 0): Flow<List<PendingChange>> =
    db.observe(db.tracked + PENDING_CHANGES) { pendingChanges(limit, offset) }

/** Deletes exactly these rows. A change written after they were read stays. */
context(_: WriteScope)
public suspend fun removePendingChanges(changes: List<PendingChange>) {
    if (changes.isEmpty()) return
    context(connection()) { Queries.deletePendingChanges.execute(changes.map { it.id }) }
    changed(PENDING_CHANGES)
}
