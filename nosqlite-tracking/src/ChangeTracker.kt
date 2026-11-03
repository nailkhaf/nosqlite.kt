package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.ReadScope
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.invoke
import io.github.nailkhaf.nosqlite.list
import io.github.nailkhaf.nosqlite.observe
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Reads and trims the [PendingChanges] log of [db]. */
public class ChangeTracker(public val db: NoSqlite) {
    /** Recorded changes for [collection], oldest first. UUIDv7 ids sort by time. */
    context(_: ReadScope)
    public suspend fun pending(collection: TrackedCollection): List<PendingChange> =
        PendingChanges.byCollection(collection.name).list {
            orderBy { "id".asc }
        }

    /** [pending], now and after every committed write that changed [PendingChanges]. */
    public fun observePending(collection: TrackedCollection): Flow<List<PendingChange>> =
        with(db) {
            PendingChanges.byCollection(collection.name).observe {
                orderBy { "id".asc }
            }
        }

    /** Deletes these changes from the log. The documents they describe stay. The result is how many were removed. */
    context(_: WriteScope)
    public suspend fun acknowledge(changes: Iterable<PendingChange>): Int {
        val ids = changes.map { it.id.toString() }
        if (ids.isEmpty()) return 0
        return PendingChanges.delete { "id" isIn ids }
    }

    /** Runs [block] in a write that changes documents without recording [PendingChange]s. */
    public suspend fun <R> untracked(block: suspend context(WriteScope) () -> R): R =
        db.write { runUntracked(block) }

    context(scope: WriteScope)
    private suspend fun <R> runUntracked(block: suspend context(WriteScope) () -> R): R =
        withContext(Untracked()) { context(scope) { block() } }
}

internal class Untracked : AbstractCoroutineContextElement(Untracked) {
    internal companion object : CoroutineContext.Key<Untracked>
}

internal suspend fun isUntracked(): Boolean = currentCoroutineContext()[Untracked] != null
