package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The documents [query] selects, now and after every committed write that changed this collection: `with(db) {
 * Users.observe { where { "age" gte 18 } } }`. Writes that land while a read runs are folded into the next one, and a
 * read that finds what the previous one found emits nothing.
 */
context(db: NoSqlite)
public inline fun <reified T> Collection.observe(noinline query: QueryScope.() -> Unit = {}): Flow<List<T>> =
    observe(serializer(), query)

context(db: NoSqlite)
public fun <T> Collection.observe(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): Flow<List<T>> {
    val query = query(query)
    return db.observe(this) { list(serializer, query) }
}

/** The documents found, now and after every committed write that changed the collection. */
context(db: NoSqlite)
public inline fun <reified T> Lookup.Many.observe(noinline query: QueryScope.() -> Unit = {}): Flow<List<T>> =
    observe(serializer(), query)

context(db: NoSqlite)
public fun <T> Lookup.Many.observe(serializer: KSerializer<T>, query: QueryScope.() -> Unit = {}): Flow<List<T>> =
    db.observe(index.collection) { list(serializer, query) }

/** The document found, `null` while there is none, now and after every committed write to the collection. */
context(db: NoSqlite)
public inline fun <reified T> Lookup.Unique.observe(): Flow<T?> = observe(serializer<T>())

context(db: NoSqlite)
public fun <T> Lookup.Unique.observe(serializer: KSerializer<T>): Flow<T?> =
    db.observe(index.collection) { get(serializer) }

private fun <R> NoSqlite.observe(collection: Collection, read: suspend context(ReadScope) () -> R): Flow<R> = flow {
    emitAll(
        versions.map { it[collection.name] }
            .distinctUntilChanged()
            .map { read(read) },
    )
}.distinctUntilChanged()
