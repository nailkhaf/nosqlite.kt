package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One recorded insert, update or delete of a tracked collection, in the order it was committed.
 * [uid] is a random UUID assigned when the row is inserted and never changed, so a sync layer can use it as the remote
 * document id and a retried upload writes the same document. [id] is only the local queue position.
 * [before] is the document before the change, null for an insert. [after] is the document after the change, null
 * for a delete. An update stores both. An update whose stored JSON did not change is not recorded.
 */
public data class PendingChange(
    public val id: Long,
    public val uid: Uuid,
    public val collection: String,
    public val before: JsonObject?,
    public val after: JsonObject?,
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

/** The pending changes recorded so far, ordered by id. */
context(_: ReadScope)
public suspend fun pendingChanges(): List<PendingChange> = context(connection()) { Queries.pendingChanges.execute() }

/** Deletes exactly these rows. A change written after they were read stays. */
context(_: WriteScope)
public suspend fun removePendingChanges(changes: List<PendingChange>) {
    if (changes.isEmpty()) return
    context(connection()) { Queries.deletePendingChanges.execute(changes.map { it.id }) }
}
