package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

// SQLite's bound-variable cap is 32766 by default and 999 on older builds. Stay under both.
private const val MaxBoundVariables = 500

/** One recorded insert, update or delete of a tracked collection, in the order it was committed. */
public data class PendingChange(
    public val id: Long,
    public val collection: String,
    public val before: JsonObject?,
    public val after: JsonObject?,
    public val timestamp: Instant,
)

/** The pending changes recorded so far, ordered by id. Empty when nothing is tracked. */
context(_: ReadScope)
public suspend fun pendingChanges(): List<PendingChange> = readPendingChanges()

/** Deletes exactly these rows. A change written after they were read stays. */
context(scope: WriteScope)
public suspend fun removePendingChanges(changes: List<PendingChange>) {
    if (changes.isEmpty()) return
    var deleted = 0
    val connection = connection()
    for (ids in changes.map { it.id }.chunked(MaxBoundVariables)) {
        deleted += context(connection) { Queries.deletePendingChanges(ids).execute() }
    }
    if (deleted > 0) scope.changed += PENDING_CHANGES
}

context(scope: ReadScope)
internal suspend fun readPendingChanges(): List<PendingChange> {
    if (!scope.pool.tracks) return emptyList()
    return context(connection()) { Queries.pendingChanges.execute() }
}
