package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The documents [query] selects, now and after every committed write that changed this collection: `with(db) {
 * Users.observe { where { "age" gte 18 } } }`. Writes that land while a read runs are folded into the next one, and a
 * read that finds what the previous one found emits nothing.
 */
context(db: NoSqlite)
public fun <T> Collection<T>.observe(query: QueryScope.() -> Unit = {}): Flow<List<T>> {
    val query = query(query)
    return db.observe(this) { list(query) }
}

/** The documents found, now and after every committed write that changed the collection. */
context(db: NoSqlite)
public fun <T> Lookup.Many<T>.observe(query: QueryScope.() -> Unit = {}): Flow<List<T>> =
    db.observe(index.collection) { list(query) }

/** The document found, `null` while there is none, now and after every committed write to the collection. */
context(db: NoSqlite)
public fun <T> Lookup.Unique<T>.observe(): Flow<T?> = db.observe(index.collection) { get() }

private fun <R> NoSqlite.observe(collection: Collection<*>, read: suspend context(ReadScope) () -> R): Flow<R> = flow {
    emitAll(
        changes.filter { it == collection.name }
            .map { }
            .onStart { emit(Unit) }
            .conflate()
            .map { read(read) },
    )
}.distinctUntilChanged()
