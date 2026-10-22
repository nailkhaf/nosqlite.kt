package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The documents [query] selects in [db], now and after every committed write that changed this collection. Writes
 * that land while a read runs are folded into the next one, and a read that finds what the previous one found emits
 * nothing.
 */
public fun <T> Collection<T>.observe(db: Database, query: QueryScope.() -> Unit = {}): Flow<List<T>> {
    val query = query(query)
    return db.observe(this) { list(query) }
}

/** The documents found in [db], now and after every committed write that changed the collection. */
public fun <T> Lookup.Many<T>.observe(db: Database, query: QueryScope.() -> Unit = {}): Flow<List<T>> =
    db.observe(index.collection) { list(query) }

/** The document found in [db], `null` while there is none, now and after every committed write to the collection. */
public fun <T> Lookup.Unique<T>.observe(db: Database): Flow<T?> = db.observe(index.collection) { get() }

// Each new version is read in its own transaction; the StateFlow conflates the versions written meanwhile.
private fun <R> Database.observe(collection: Collection<*>, read: suspend context(ReadScope) () -> R): Flow<R> = flow {
    if (collection.name !in versions.value) throw NosqliteException.CollectionNotDeclared(collection.name)
    emitAll(versions.map { it.getValue(collection.name) }.distinctUntilChanged().map { read(read) })
}.distinctUntilChanged()
