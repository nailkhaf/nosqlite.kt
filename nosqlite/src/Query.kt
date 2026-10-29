package io.github.nailkhaf.nosqlite

/** A sort key: a field and its direction. */
internal class Order(val path: Path, val descending: Boolean) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"
}

internal class Query(val condition: Where?, val orderBy: List<Order>, val limit: Int, val offset: Int)

/**
 * Builds a query: which documents, in what order, and which page of them. Each part is set at most once; without
 * one, every document matches, in an unspecified order, all of them:
 * ```
 * Users.list {
 *     where { "age" gte 18 }
 *     orderBy {
 *         "age".desc
 *         "name.last".asc
 *     }
 *     limit(20)
 *     offset(40)
 * }
 * ```
 * The order of documents with equal sort keys is unspecified, so add a unique field as the last sort key when a
 * stable order or offset pagination matters.
 */
@QueryDsl
public class QueryScope internal constructor() {
    private var condition: Where? = null
    private var orderBy: List<Order>? = null
    private var limit: Int? = null
    private var offset: Int? = null

    public fun where(condition: WhereScope.() -> Where) {
        check(this.condition == null) { "where is already set" }
        this.condition = WhereScope().condition()
    }

    /** Sort keys in order, each a field followed by `.asc` or `.desc`. */
    public fun orderBy(keys: OrderScope.() -> Unit) {
        check(orderBy == null) { "orderBy is already set" }
        orderBy = OrderScope().apply(keys).keys.toList()
    }

    public fun limit(limit: Int) {
        check(this.limit == null) { "limit is already set" }
        require(limit > 0) { "Limit must be positive: $limit" }
        this.limit = limit
    }

    public fun offset(offset: Int) {
        check(this.offset == null) { "offset is already set" }
        require(offset >= 0) { "Offset must not be negative: $offset" }
        this.offset = offset
    }

    internal fun build(): Query = Query(condition, orderBy.orEmpty(), limit ?: Int.MAX_VALUE, offset ?: 0)
}

/** Collects sort keys: `"age".desc` sorts by age, largest first. */
@QueryDsl
public class OrderScope internal constructor() {
    internal val keys: MutableList<Order> = mutableListOf()

    /** Smallest first; null and missing values come first. */
    public val String.asc: Unit get() = add(Order(Path(this), descending = false))

    /** Largest first; null and missing values come last. */
    public val String.desc: Unit get() = add(Order(Path(this), descending = true))

    internal fun add(order: Order) {
        keys += order
    }
}

internal fun query(block: QueryScope.() -> Unit): Query = QueryScope().apply(block).build()
