package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The documents [query] selects, now and after every committed write that changed this collection:
 * `with(db) { Users.observe<User> { where { "age" gte 18 } } }`. Writes that land while a read runs are folded into
 * the next one, and a read that finds what the previous one found emits nothing.
 */
context(db: NoSqlite)
public inline fun <reified T> Collection.observe(noinline query: QueryBlock = {}): Flow<List<T>> =
    observe(serializer(), query)

/** As the reified overload, with an explicit [serializer]. */
context(db: NoSqlite)
public fun <T> Collection.observe(serializer: KSerializer<T>, query: QueryBlock = {}): Flow<List<T>> {
    val query = query(query)
    return db.observe(listOf(name)) { list(serializer, query) }
}

/** The documents found, now and after every committed write that changed the collection. */
context(db: NoSqlite)
public inline fun <reified T> Lookup.observe(noinline query: QueryBlock = {}): Flow<List<T>> =
    observe(serializer(), query)

/** As the reified overload, with an explicit [serializer]. */
context(db: NoSqlite)
public fun <T> Lookup.observe(serializer: KSerializer<T>, query: QueryBlock = {}): Flow<List<T>> =
    db.observe(listOf(index.collection.name)) { list(serializer, query) }

/** The document with this id, `null` while there is none, now and after every committed write to the collection. */
context(db: NoSqlite)
public inline fun <reified T> Id.observe(): Flow<T?> = observe(serializer<T>())

/** As the reified overload, with an explicit [serializer]. */
context(db: NoSqlite)
public fun <T> Id.observe(serializer: KSerializer<T>): Flow<T?> =
    db.observe(listOf(collection.name)) { get(serializer) }

// Reads now, and again when the version of any of [names] moves.
internal fun <R> NoSqlite.observe(names: List<String>, read: suspend context(ReadScope) () -> R): Flow<R> = flow {
    emitAll(
        versions.map { versions -> names.map { versions[it] } }
            .distinctUntilChanged()
            .map { read(read) },
    )
}.distinctUntilChanged()
