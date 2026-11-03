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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads and trims the [PendingChanges] log of [db]. [clock] stamps each change recorded after this tracker is
 * constructed; the latest tracker supplies the clock.
 */
public class ChangeTracker(public val db: NoSqlite, public val clock: Clock = Clock.System) {
    init {
        TrackingClock.install(clock)
    }

    /** Recorded changes for [collection], oldest first. */
    context(_: ReadScope)
    public suspend fun pending(collection: TrackedCollection): List<PendingChange> =
        PendingChanges.byCollection(collection.name).list<PendingChange>().sortedWith(pendingOrder)

    /** [pending], now and after every committed write that changed [PendingChanges]. */
    public fun observePending(collection: TrackedCollection): Flow<List<PendingChange>> =
        with(db) { PendingChanges.byCollection(collection.name).observe<PendingChange>() }
            .map { it.sortedWith(pendingOrder) }

    /** Deletes these changes from the log. The documents they describe stay. The result is how many were removed. */
    context(_: WriteScope)
    public suspend fun acknowledge(changes: Iterable<PendingChange>): Int {
        val ids = changes.map { it.id.toString() }
        if (ids.isEmpty()) return 0
        return PendingChanges.delete { "id" isIn ids }
    }

    /** Runs [block] in a write that changes documents without recording [PendingChange]s. */
    public suspend fun <R> applyRemote(block: suspend context(WriteScope) () -> R): R =
        db.write { runUnrecorded(block) }

    context(scope: WriteScope)
    private suspend fun <R> runUnrecorded(block: suspend context(WriteScope) () -> R): R =
        withContext(ApplyRemote()) { context(scope) { block() } }
}

private val pendingOrder = compareBy<PendingChange>({ it.timestamp }, { it.id.toString() })

internal object TrackingClock {
    private val current = AtomicReference<Clock>(Clock.System)

    fun install(clock: Clock) {
        current.store(clock)
    }

    fun now(): Instant = current.load().now()
}

internal class ApplyRemote : AbstractCoroutineContextElement(ApplyRemote) {
    internal companion object : CoroutineContext.Key<ApplyRemote>
}

internal suspend fun isApplyingRemote(): Boolean = currentCoroutineContext()[ApplyRemote] != null
