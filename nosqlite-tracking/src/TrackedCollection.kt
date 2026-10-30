package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.BaseCollection
import io.github.nailkhaf.nosqlite.ExperimentalWriteHook
import io.github.nailkhaf.nosqlite.PrimaryKey
import io.github.nailkhaf.nosqlite.WriteInterceptor
import io.github.nailkhaf.nosqlite.database
import io.github.nailkhaf.nosqlite.WriteScope
import io.github.nailkhaf.nosqlite.insert
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/**
 * A [BaseCollection] whose writes are tracked. It has no where-based bulk write: insert, upsert, and update or delete
 * by its primary key or a unique index each touch one document and record one [PendingChange] in that transaction.
 * [id] is required. A database that declares one creates [PendingChanges] on open.
 */
@OptIn(ExperimentalWriteHook::class)
public abstract class TrackedCollection(name: String) : BaseCollection(name) {
    /** The primary key every document is identified by. An update cannot change it. */
    public abstract override val id: PrimaryKey

    init {
        declareAlongside(PendingChanges)
        checkOnOpen {
            require(id.collection === this) {
                "Tracked collection '$name' primary key belongs to '${id.collection.name}'"
            }
        }
        intercept(TrackingInterceptor(this))
    }
}

@OptIn(ExperimentalWriteHook::class)
private class TrackingInterceptor(private val collection: TrackedCollection) : WriteInterceptor {
    context(_: WriteScope)
    override suspend fun insert(document: JsonObject) {
        if (applyingRemote()) return
        record(PendingChange.Action.Insert, document)
    }

    context(_: WriteScope)
    override suspend fun update(before: JsonObject, after: JsonObject) {
        if (applyingRemote()) return
        val patch = diffJsonMergePatch(before, after)
        if (patch.isEmpty()) return
        record(PendingChange.Action.Update, patch.withFields(after.primaryKeyObject(collection.id.fields.single())))
    }

    context(_: WriteScope)
    override suspend fun delete(document: JsonObject) {
        if (applyingRemote()) return
        record(PendingChange.Action.Delete, document.primaryKeyObject(collection.id.fields.single()))
    }

    context(_: WriteScope)
    private suspend fun record(action: PendingChange.Action, changeSet: JsonObject) {
        PendingChanges.insert(
            PendingChange(
                id = Uuid.generateV7(),
                collection = collection.name,
                action = action,
                changeSet = changeSet,
                timestamp = collection.database.trackingClock.now(),
            ),
        )
    }
}

private suspend fun applyingRemote(): Boolean = coroutineContext[ApplyRemote] != null

internal class ApplyRemote : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    internal companion object Key : kotlin.coroutines.CoroutineContext.Key<ApplyRemote>
}
