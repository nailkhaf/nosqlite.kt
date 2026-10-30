package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.BaseCollection
import io.github.nailkhaf.nosqlite.ExperimentalWriteHook
import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.ReadScope
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.count
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.list
import io.github.nailkhaf.nosqlite.observe
import io.github.nailkhaf.nosqlite.invoke
import io.github.nailkhaf.nosqlite.read
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Reads and acknowledges [PendingChange]s recorded for tracked collections of [db]. [clock] stamps each change.
 * Writes made in [applyRemote] are stored and not recorded, for changes pulled from elsewhere.
 */
@OptIn(ExperimentalWriteHook::class)
public class ChangeTracker(public val db: NoSqlite, public val clock: Clock = Clock.System) {
    init {
        db.trackingClock = clock
    }

    /** Whether every change has been acknowledged. */
    public suspend fun isEmpty(): Boolean = db.read { PendingChanges.count() == 0L }

    /** The changes to [collection] not acknowledged yet, in the order they were made. */
    context(_: ReadScope)
    public suspend fun pending(collection: BaseCollection): List<PendingChange> =
        PendingChanges.byCollection(collection.name).list(PendingChange.serializer()) { orderBy { "id".asc } }

    /** [pending] now and after every committed write that changed [PendingChanges]. */
    public fun observePending(collection: BaseCollection): Flow<List<PendingChange>> = context(db) {
        PendingChanges.byCollection(collection.name).observe(PendingChange.serializer()) { orderBy { "id".asc } }
    }

    /** Removes [changes] once they are synced; the result is how many were still pending. */
    context(_: WriteScope)
    public suspend fun acknowledge(changes: Iterable<PendingChange>): Int {
        val ids = changes.map { it.id.toString() }
        if (ids.isEmpty()) return 0
        return PendingChanges.delete { "id" isIn ids }
    }

    /**
     * Runs [block] as a write in which tracked collections are updated and nothing is recorded. Inside a write it is
     * a savepoint of that write.
     */
    public suspend fun <R> applyRemote(block: suspend context(WriteScope) () -> R): R = db.write {
        withContext(ApplyRemote()) { block() }
    }
}
