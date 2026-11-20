package io.github.nailkhaf.nosqlite

/** A sort key: a field and its direction, and the collection whose index it came from, if any. */
internal class Order(val path: Path, val descending: Boolean, val collection: Collection? = null) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"
}

/** The parts of a query, set in [QueryScope]: `{ where { "age" gte 18 } }`. */
public typealias QueryBlock = QueryScope.() -> Unit

internal data class Query(val condition: Where?, val orderBy: List<Order>, val limit: Int, val offset: Int)

/**
 * Builds a query: which documents, in what order, and which page. Each part is set at most once:
 * ```
 * Users.list<User> {
 *     where { Users.byAge gte 18 }
 *     orderBy { Users.byAge.desc }
 *     limit(20)
 *     offset(40)
 * }
 * ```
 * Without `orderBy`, documents come in id order. With it, the id is always the last sort key, in the direction of
 * the key before it, so the order is fixed.
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

    /**
     * Sort keys in order: an index or a field, followed by `.asc` or `.desc`. Prefer an index: SQLite reads it in
     * order, so the sort costs nothing.
     */
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

/**
 * Collects sort keys: `Users.byAge.desc` sorts by the index's fields, largest first. A field such as `"score".desc`
 * sorts too, but SQLite has to sort in memory unless an index has exactly the keys given, and sorting by only the
 * first fields of a longer index stops it from reading that index.
 */
@QueryDsl
public class OrderScope internal constructor() {
    internal val keys: List<Order>
        field = mutableListOf()

    /** By every field of the index, then its id, smallest first; null and missing values come first. */
    public val Index.asc: Unit get() = add(this, descending = false)

    /** By every field of the index, then its id, largest first; null and missing values come last. */
    public val Index.desc: Unit get() = add(this, descending = true)

    /** Smallest first; null and missing values come first. */
    public val String.asc: Unit get() = add(Order(Path(this), descending = false))

    /** Largest first; null and missing values come last. */
    public val String.desc: Unit get() = add(Order(Path(this), descending = true))

    internal fun add(order: Order) {
        keys += order
    }

    private fun add(index: Index, descending: Boolean) {
        for (path in index.sortPaths + Path(ID)) add(Order(path, descending, index.collection))
    }
}

internal fun query(block: QueryBlock): Query = QueryScope().apply(block).build()
