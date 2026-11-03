package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.Collection
import io.github.nailkhaf.nosqlite.Index
import io.github.nailkhaf.nosqlite.UniqueIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One recorded write. [changeSet] always contains the primary key: the encoded value for [Action.Upsert], or only
 * that field for [Action.Delete].
 */
@Serializable
public data class PendingChange(
    public val id: Uuid,
    public val collection: String,
    public val action: Action,
    public val changeSet: JsonObject,
    public val timestamp: Instant,
) {
    @Serializable
    public enum class Action { Upsert, Delete }
}

/**
 * The log of pending changes. An ordinary collection: declare it beside the tracked ones,
 * `NoSqlite { +Contacts; +PendingChanges }`.
 */
public object PendingChanges : Collection("__pending_changes") {
    public val byId: UniqueIndex = unique(PendingChange::id)
    /** One collection's changes. Order a read by [PendingChange.id]; a UUIDv7 sorts by time. */
    public val byCollection: Index = index(PendingChange::collection)
}
