package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Up to [limit] matching documents of [db], now and after every committed write to the collection. Writes that land
 * while a read runs are folded into the next one, and a read that finds what the previous one found emits nothing.
 */
public fun <T> Select<T>.observe(db: Database, limit: Int): Flow<List<T>> {
    require(limit >= 0) { "Negative limit: $limit" }
    return db.observe(collection) { list(limit) }
}

/** The document [id] of [db], now and after every committed write to this collection; `null` while there is none. */
public inline fun <reified T> Collection.observe(db: Database, id: DocumentId): Flow<T?> =
    observe(db, id, serializer<T>())

@PublishedApi
internal fun <T> Collection.observe(db: Database, id: DocumentId, serializer: KSerializer<T>): Flow<T?> =
    db.observe(this) { get(id, serializer) }

// Each new version is read in its own transaction; StateFlow conflates the versions written meanwhile.
internal fun <R> Database.observe(collection: Collection, query: suspend context(ReadScope) () -> R): Flow<R> =
    version(collection).map { read(query) }.distinctUntilChanged()
