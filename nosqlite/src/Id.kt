package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

internal fun idCondition(value: Uuid): Where =
    Where.Compare(Path(ID), Comparison.Eq, SqlValue.Text(value.toString()))

/**
 * The document of a collection whose id is [value]. There is at most one.
 * The document's `id` field stores [Uuid.toString].
 * ```
 * Users.id(ada).get<User>()
 * Users.id(ada).update(ada.copy(age = 37))
 * Users.upsert(ada)
 * ```
 */
public class Id internal constructor(internal val collection: Collection, public val value: Uuid) {
    internal val condition: Where = idCondition(value)

    override fun toString(): String = "${collection.name} id $value"
}

/** The document of this collection with [value] as its id. */
public fun Collection.id(value: Uuid): Id = Id(this, value)

/** The document as [T], `null` when there is none. */
context(_: ReadScope)
public suspend inline fun <reified T> Id.get(): T? = get(serializer<T>())

/** As the reified overload, with an explicit [serializer]. */
context(_: ReadScope)
public suspend fun <T> Id.get(serializer: KSerializer<T>): T? =
    collection.list(serializer, Query(condition, emptyList(), limit = 1, offset = 0)).firstOrNull()

/**
 * Updates the document with [value], as `update` does: a merge patch with [merge], the whole document without it, and
 * then [value] must carry the same id. The result is whether there was one.
 */
context(_: WriteScope)
public suspend inline fun <reified T> Id.update(value: T, merge: Boolean = true): Boolean =
    update(serializer(), value, merge)

/** As the reified overload, with an explicit [serializer]. */
context(_: WriteScope)
public suspend fun <T> Id.update(serializer: KSerializer<T>, value: T, merge: Boolean = true): Boolean =
    collection.update(serializer, value, merge) { condition } > 0

/** Deletes the document; the result is whether there was one. */
context(_: WriteScope)
public suspend fun Id.delete(): Boolean = collection.delete { condition } > 0
