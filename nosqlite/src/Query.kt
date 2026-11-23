package io.github.nailkhaf.nosqlite

/** A sort key: a field and its direction. */
internal class Order(val path: Path, val descending: Boolean) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"
}

/** The parts of a query, set in [QueryScope]: `{ where { "age" gte 18 } }`. */
public typealias QueryBlock = QueryScope.() -> Unit

internal data class Query(val condition: Where?, val orderBy: List<Order>, val limit: Int, val offset: Int)

/**
 * Builds a query: which documents, in what order, and which page. Each part is set at most once:
 * ```
 * Users.list<User> {
 *     where { "age" gte 18 }
 *     orderBy {
 *         "age".desc
 *         "name.last".asc
 *     }
 *     limit(20)
 *     offset(40)
 * }
 * ```
 * Documents with equal sort keys come in an unspecified order; sort by `id` last for stable pages.
 */
@QueryDsl
public class QueryScope internal constructor() {
    private var condition: Where? = null
    private var orderBy: List<Order>? = null
    private var limit: Int? = null
    private var offset: Int? = null

    /** Which documents match [condition]. */
    public fun where(condition: Condition) {
        check(this.condition == null) { "where is already set" }
        this.condition = WhereScope().condition()
    }

    /** Sort keys in order, each a field followed by `.asc` or `.desc`. */
    public fun orderBy(keys: OrderScope.() -> Unit) {
        check(orderBy == null) { "orderBy is already set" }
        orderBy = OrderScope().apply(keys).keys.toList()
    }

    /** At most [limit] documents; must be positive. */
    public fun limit(limit: Int) {
        check(this.limit == null) { "limit is already set" }
        require(limit > 0) { "Limit must be positive: $limit" }
        this.limit = limit
    }

    /** Skips the first [offset] documents; must not be negative. */
    public fun offset(offset: Int) {
        check(this.offset == null) { "offset is already set" }
        require(offset >= 0) { "Offset must not be negative: $offset" }
        this.offset = offset
    }

    internal fun build(): Query = Query(condition, orderBy.orEmpty(), limit ?: Int.MAX_VALUE, offset ?: 0)
}

/** Collects sort keys: `"age".desc`, `User::age.desc`, or an index (`Users.byName.asc`). */
@QueryDsl
public class OrderScope internal constructor() {
    internal val keys: List<Order>
        field = mutableListOf()

    /** Smallest first; null and missing values come first. */
    public val String.asc: Unit get() = add(Order(Path(this), descending = false))

    /** Largest first; null and missing values come last. */
    public val String.desc: Unit get() = add(Order(Path(this), descending = true))

    /** Walks this index forward: each of its keys in its declared direction. */
    public val Index.asc: Unit
        get() {
            for (key in this.keys) add(key)
        }

    /** Walks this index backward: each of its keys in the reverse of its declared direction. */
    public val Index.desc: Unit
        get() {
            for (key in this.keys) add(Order(key.path, descending = !key.descending))
        }

    /** Smallest id first (oldest first with UUID v7). */
    public val IdIndex.asc: Unit get() = add(Order(Path(ID), descending = false))

    /** Largest id first (newest first with UUID v7). */
    public val IdIndex.desc: Unit get() = add(Order(Path(ID), descending = true))

    internal fun add(order: Order) {
        keys += order
    }
}

internal fun query(block: QueryBlock): Query = QueryScope().apply(block).build()
