package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/** A sort key: a field and its direction. Ties are always broken by insertion order, the `rowid`. */
public class Order internal constructor(internal val path: Path<*, *>, internal val descending: Boolean) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"
}

/**
 * A query on a collection, built by [select] and run by a terminal: `first()`, `list(limit)` or `count()` in a
 * transaction. Building it touches no database, so one can be kept and run many times.
 */
public class Select<T> @PublishedApi internal constructor(
    public val collection: Collection,
    internal val serializer: KSerializer<T>,
    internal val where: Condition?,
    internal val orders: List<Order>,
) {
    override fun toString(): String = buildString {
        append("select from ${collection.name}")
        where?.let { append(" where $it") }
        if (orders.isNotEmpty()) append(orders.joinToString(", ", prefix = " order by "))
    }
}

/** Filters with [where] and sorts with [orderBy]; without [orderBy] documents come in insertion order. */
@QueryDsl
public class SelectScope @PublishedApi internal constructor() {
    private var where: Condition? = null
    private val orders = mutableListOf<Order>()

    /** Keeps documents matching [block]; several calls must all match. */
    public fun where(block: WhereScope.() -> Condition) {
        val condition = WhereScope().block()
        where = where?.let { Condition.And(it, condition) } ?: condition
    }

    /** Sorts by [orders], first one first: `orderBy(User::age.desc, User::name / Name::last)`. */
    public fun orderBy(vararg orders: Order) {
        this.orders += orders
    }

    /** Sorts by [properties] ascending. */
    public fun orderBy(vararg properties: KProperty1<*, *>) {
        properties.mapTo(orders) { it.asc }
    }

    /** Sorts by this property, smallest first; null and missing values come first. */
    public val KProperty1<*, *>.asc: Order get() = toPath().asc

    /** Sorts by this property, largest first; null and missing values come last. */
    public val KProperty1<*, *>.desc: Order get() = toPath().desc

    /** Sorts by this field, smallest first; null and missing values come first. */
    public val Path<*, *>.asc: Order get() = Order(this, descending = false)

    /** Sorts by this field, largest first; null and missing values come last. */
    public val Path<*, *>.desc: Order get() = Order(this, descending = true)

    @PublishedApi
    internal fun <T> build(collection: Collection, serializer: KSerializer<T>): Select<T> =
        Select(collection, serializer, where, orders.toList())
}

/**
 * A query whose documents decode as [T]: a smaller class than the stored one reads only its fields.
 * ```
 * Users.select<User> {
 *     where { (User::age gte 18) and (User::tags contains "kotlin") }
 *     orderBy(User::age.desc)
 * }
 * ```
 */
public inline fun <reified T> Collection.select(block: SelectScope.() -> Unit = {}): Select<T> =
    SelectScope().apply(block).build(this, serializer<T>())

/** The first matching document, `null` when there is none. */
context(_: ReadScope)
public suspend fun <T> Select<T>.first(): T? = list(1).firstOrNull()

/** Up to [limit] matching documents, in order. */
context(_: ReadScope)
public suspend fun <T> Select<T>.list(limit: Int): List<T> {
    require(limit >= 0) { "Negative limit: $limit" }
    return context(collection.connection()) {
        Queries.select(this, limit).execute().map(::decode)
    }
}

/** How many documents match. */
context(_: ReadScope)
public suspend fun Select<*>.count(): Long = context(collection.connection()) { Queries.count(this).execute() }

/** The document in [row] decoded as this select's type. */
internal fun <T> Select<T>.decode(row: Row): T = collection.decode(row, serializer)
