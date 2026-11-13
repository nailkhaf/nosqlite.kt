package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One recorded insert, update or delete of a tracked collection, in the order it was committed.
 * [documentId] is the document's `id`, a [Uuid]. [changeSet] is the new document for an [Action.Insert], `{}` for an
 * [Action.Delete], and a JSON merge patch for an [Action.Update]: nested objects are diffed recursively, arrays are
 * replaced whole, and a removed key is `null` at the outermost removed path.
 */
public data class PendingChange(
    public val id: Long,
    public val collection: String,
    public val documentId: Uuid,
    public val action: Action,
    public val changeSet: JsonObject,
    public val timestamp: Instant,
) {
    /** What happened to [documentId]. */
    public enum class Action { Insert, Update, Delete }
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
