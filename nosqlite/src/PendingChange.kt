package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

/**
 * The pending changes recorded so far, ordered by id, now and again after every committed write that recorded or
 * removed any. Writes that land while a read runs are folded into the next one.
 */
public fun NoSqlite.pendingChanges(): Flow<List<PendingChange>> = flow {
    emitAll(
        versions.map { it[PENDING_CHANGES] }
            .distinctUntilChanged()
            .map { read { readPendingChanges() } },
    )
}.distinctUntilChanged()

/** The pending changes recorded so far, ordered by id. */
context(_: ReadScope)
public suspend fun pendingChanges(): List<PendingChange> = readPendingChanges()

/** Deletes exactly these rows in one statement. A change written after they were read stays. */
context(scope: WriteScope)
public suspend fun removePendingChanges(changes: List<PendingChange>) {
    if (changes.isEmpty()) return
    val deleted = context(connection()) { Queries.deletePendingChanges(changes.map { it.id }).execute() }
    if (deleted > 0) scope.changed += PENDING_CHANGES
}

context(_: ReadScope)
private suspend fun readPendingChanges(): List<PendingChange> =
    context(connection()) { Queries.pendingChanges.execute() }
