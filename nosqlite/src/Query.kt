package io.github.nailkhaf.nosqlite

/** A sort key: a field and its direction, made by [QueryScope.asc] and [QueryScope.desc]. */
public class Order internal constructor(internal val path: Path, internal val descending: Boolean) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"
}

internal class Query(val condition: Condition?, val orderBy: List<Order>, val limit: Int, val offset: Int)

/**
 * Builds a query: which documents, in what order, and which page of them. Each part is set at most once; without
 * one, every document matches, in the order they were inserted, all of them:
 * ```
 * Users.list {
 *     where { "age" gte 18 }
 *     orderBy(desc("age"), asc("name.last"))
 *     limit(20)
 *     offset(40)
 * }
 * ```
 * Documents with equal sort keys keep insertion order.
 */
@QueryDsl
public class QueryScope internal constructor() {
    private var condition: Condition? = null
    private var orderBy: List<Order>? = null
    private var limit: Int? = null
    private var offset: Int? = null

    public fun where(condition: WhereScope.() -> Condition) {
        check(this.condition == null) { "where is already set" }
        this.condition = WhereScope().condition()
    }

    public fun orderBy(vararg orders: Order) {
        check(orderBy == null) { "orderBy is already set" }
        orderBy = orders.toList()
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

    /** Smallest first; null and missing values come first. */
    public fun asc(field: String): Order = Order(Path(field), descending = false)

    /** Largest first; null and missing values come last. */
    public fun desc(field: String): Order = Order(Path(field), descending = true)

    internal fun build(): Query = Query(condition, orderBy.orEmpty(), limit ?: Int.MAX_VALUE, offset ?: 0)
}

internal fun query(block: QueryScope.() -> Unit): Query = QueryScope().apply(block).build()
