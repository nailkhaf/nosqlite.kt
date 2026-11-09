package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** One recorded insert, update or delete of a tracked collection, in the order it was committed. */
public data class PendingChange(
    public val id: Long,
    public val collection: String,
    public val before: JsonObject?,
    public val after: JsonObject?,
    public val timestamp: Instant,
)

/** The pending changes recorded so far, ordered by id. */
context(_: ReadScope)
public suspend fun pendingChanges(): List<PendingChange> = context(connection()) { Queries.pendingChanges.execute() }

/** Deletes exactly these rows. A change written after they were read stays. */
context(_: WriteScope)
public suspend fun removePendingChanges(changes: List<PendingChange>) {
    if (changes.isEmpty()) return
    context(connection()) { Queries.deletePendingChanges.execute(changes.map { it.id }) }
}
