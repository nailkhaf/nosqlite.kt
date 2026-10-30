package io.github.nailkhaf.nosqlite.tracking

import io.github.nailkhaf.nosqlite.Collection
import io.github.nailkhaf.nosqlite.Index
import io.github.nailkhaf.nosqlite.UniqueIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A write to a tracked document that hasn't been acknowledged yet. Changes are made in the order of their [id], a
 * version 7 UUID, within one process. The document is identified by its primary key inside [changeSet].
 */
@Serializable
public data class PendingChange(
    public val id: Uuid,
    /** The name of the tracked collection. */
    public val collection: String,
    public val action: Action,
    /** The write. It always includes the primary key. */
    public val changeSet: JsonObject,
    public val timestamp: Instant,
) {
    @Serializable
    public enum class Action {
        /** A new document; [changeSet] is its full content. */
        Insert,

        /**
         * A change to an existing document. [changeSet] is an RFC 7396 merge patch that includes the primary key
         * even when it did not change.
         */
        Update,

        /** A deleted document; [changeSet] holds only the primary key. */
        Delete,
    }
}

/**
 * The collection of [PendingChange]s. A database that declares a [TrackedCollection] creates it on open; it can also
 * be declared explicitly.
 */
public object PendingChanges : Collection("__pending_changes") {
    override val byId: UniqueIndex = unique(PendingChange::id)

    /** A collection's changes; order them by `id` to get them in the order they were made. */
    public val byCollection: Index = index(PendingChange::collection, PendingChange::id)
}
