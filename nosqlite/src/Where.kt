package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmName
import kotlin.uuid.Uuid

/** A filter on documents, built in [WhereScope]. */
public sealed class Where {
    internal class Compare(val path: Path, val comparison: Comparison, val value: SqlValue) : Where()

    /** The fields taken together, compared in order as SQLite compares row values: `(last, first) > (?, ?)`. */
    internal class CompareRow(val paths: List<Path>, val comparison: Comparison, val values: List<SqlValue>) : Where()
    internal class In(val path: Path, val values: List<SqlValue>) : Where()

    /** The table's key compared with [value]: an id condition, which SQLite searches the key for. */
    internal class Key(val comparison: Comparison, val value: Uuid) : Where()

    /** The table's key is one of [values], which are never empty. */
    internal class KeyIn(val values: List<Uuid>) : Where()
    internal class IsNull(val path: Path) : Where()
    internal class IsNotNull(val path: Path) : Where()
    internal class Contains(val path: Path, val value: SqlValue) : Where()
    internal class And(val left: Where, val right: Where) : Where()
    internal class Or(val left: Where, val right: Where) : Where()
    internal class Not(val condition: Where) : Where()

    override fun toString(): String = render(inline = true).sql
}

/** A filter built in [WhereScope]: `{ "age" gte 18 }`. */
public typealias Condition = WhereScope.() -> Where

/** Marks the query builders, so an outer builder's functions don't leak into an inner block. */
@DslMarker
public annotation class QueryDsl

/**
 * Builds a [Where] on fields named by their JSON keys, compared with [String], [Int], [Long] or finite [Double]
 * values, and [isTrue] or [isFalse] for booleans. [Float] has no overload, since `toDouble()` isn't exact. Other
 * types are compared as they are stored, so an enum as its serial name:
 * ```
 * ("age" gte 18) and ("name.last" eq "Lovelace") and ("role" eq "member") and ("tags" contains "kotlin")
 * ```
 */
@QueryDsl
public class WhereScope internal constructor() {
    /** The field equals [value]. */
    public infix fun String.eq(value: String): Where = compare(this, Comparison.Eq, SqlValue.Text(value))

    /** The field equals [value]. */
    public infix fun String.eq(value: Long): Where = compare(this, Comparison.Eq, value.sqlInteger)

    /** The field equals [value]. */
    public infix fun String.eq(value: Int): Where = compare(this, Comparison.Eq, value.toLong().sqlInteger)

    /** The field equals [value]. */
    public infix fun String.eq(value: Double): Where = compare(this, Comparison.Eq, value.sqlReal)

    /** The field is present and not [value]. */
    public infix fun String.neq(value: String): Where = compare(this, Comparison.Neq, SqlValue.Text(value))

    /** The field is present and not [value]. */
    public infix fun String.neq(value: Long): Where = compare(this, Comparison.Neq, value.sqlInteger)

    /** The field is present and not [value]. */
    public infix fun String.neq(value: Int): Where = compare(this, Comparison.Neq, value.toLong().sqlInteger)

    /** The field is present and not [value]. */
    public infix fun String.neq(value: Double): Where = compare(this, Comparison.Neq, value.sqlReal)

    /** The field is greater than [value]. */
    public infix fun String.gt(value: String): Where = compare(this, Comparison.Gt, SqlValue.Text(value))

    /** The field is greater than [value]. */
    public infix fun String.gt(value: Long): Where = compare(this, Comparison.Gt, value.sqlInteger)

    /** The field is greater than [value]. */
    public infix fun String.gt(value: Int): Where = compare(this, Comparison.Gt, value.toLong().sqlInteger)

    /** The field is greater than [value]. */
    public infix fun String.gt(value: Double): Where = compare(this, Comparison.Gt, value.sqlReal)

    /** The field is at least [value]. */
    public infix fun String.gte(value: String): Where = compare(this, Comparison.Gte, SqlValue.Text(value))

    /** The field is at least [value]. */
    public infix fun String.gte(value: Long): Where = compare(this, Comparison.Gte, value.sqlInteger)

    /** The field is at least [value]. */
    public infix fun String.gte(value: Int): Where = compare(this, Comparison.Gte, value.toLong().sqlInteger)

    /** The field is at least [value]. */
    public infix fun String.gte(value: Double): Where = compare(this, Comparison.Gte, value.sqlReal)

    /** The field is less than [value]. */
    public infix fun String.lt(value: String): Where = compare(this, Comparison.Lt, SqlValue.Text(value))

    /** The field is less than [value]. */
    public infix fun String.lt(value: Long): Where = compare(this, Comparison.Lt, value.sqlInteger)

    /** The field is less than [value]. */
    public infix fun String.lt(value: Int): Where = compare(this, Comparison.Lt, value.toLong().sqlInteger)

    /** The field is less than [value]. */
    public infix fun String.lt(value: Double): Where = compare(this, Comparison.Lt, value.sqlReal)

    /** The field is at most [value]. */
    public infix fun String.lte(value: String): Where = compare(this, Comparison.Lte, SqlValue.Text(value))

    /** The field is at most [value]. */
    public infix fun String.lte(value: Long): Where = compare(this, Comparison.Lte, value.sqlInteger)

    /** The field is at most [value]. */
    public infix fun String.lte(value: Int): Where = compare(this, Comparison.Lte, value.toLong().sqlInteger)

    /** The field is at most [value]. */
    public infix fun String.lte(value: Double): Where = compare(this, Comparison.Lte, value.sqlReal)

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInText")
    public infix fun String.isIn(values: Iterable<String>): Where =
        isIn(Path(this), values.map(SqlValue::Text))

    /** The field equals one of [values]; none matches nothing. A `List<Int>` needs `map(Int::toLong)`. */
    @JvmName("isInLong")
    public infix fun String.isIn(values: Iterable<Long>): Where =
        isIn(Path(this), values.map { it.sqlInteger })

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInDouble")
    public infix fun String.isIn(values: Iterable<Double>): Where =
        isIn(Path(this), values.map { it.sqlReal })

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: String): Where = Where.Contains(Path(this), SqlValue.Text(value))

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: Long): Where = Where.Contains(Path(this), value.sqlInteger)

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: Int): Where = Where.Contains(Path(this), value.toLong().sqlInteger)

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: Double): Where = Where.Contains(Path(this), value.sqlReal)

    /** The text field matches [pattern]: `%` is any run of characters, `_` any one; ASCII letters ignore case. */
    public infix fun String.like(pattern: String): Where = compare(this, Comparison.Like, SqlValue.Text(pattern))

    /** The field is `true`; `->>` gives booleans back as 1 and 0. */
    public fun String.isTrue(): Where = compare(this, Comparison.Eq, SqlValue.Integer(1))

    /** The field is `false`; a null or missing field is neither true nor false. */
    public fun String.isFalse(): Where = compare(this, Comparison.Eq, SqlValue.Integer(0))

    /** The field is null or missing. */
    public fun String.isNull(): Where = Where.IsNull(Path(this))

    /** The field is present and not null. */
    public fun String.isNotNull(): Where = Where.IsNotNull(Path(this))

    /** Both conditions hold. */
    public infix fun Where.and(other: Where): Where = Where.And(this, other)

    /** Either condition holds. */
    public infix fun Where.or(other: Where): Where = Where.Or(this, other)

    /** [condition] does not hold. */
    public fun not(condition: Where): Where = Where.Not(condition)

    /** Every field of the index equals its value, as the lookup `Users.byName("Lovelace", "Ada")` finds. */
    public operator fun FieldIndex.invoke(vararg values: Any?): Where = equal(sqlValues(values))

    /** The document with id [value], as the lookup `Users.byId(id)` finds. */
    public operator fun IdIndex.invoke(value: Uuid): Where = idCondition(value)

    /** The one field of the index equals [value], `null` matching a null or missing field. */
    public infix fun FieldIndex.eq(value: Any?): Where = equal(sqlValues(arrayOf(value)))

    /** The one field of the index is greater than [value]. */
    public infix fun FieldIndex.gt(value: Any): Where = compare(Comparison.Gt, arrayOf(value))

    /** The one field of the index is at least [value]. */
    public infix fun FieldIndex.gte(value: Any): Where = compare(Comparison.Gte, arrayOf(value))

    /** The one field of the index is less than [value]. */
    public infix fun FieldIndex.lt(value: Any): Where = compare(Comparison.Lt, arrayOf(value))

    /** The one field of the index is at most [value]. */
    public infix fun FieldIndex.lte(value: Any): Where = compare(Comparison.Lte, arrayOf(value))

    /**
     * The index's fields, taken together in order, come after [values], one per field: `Users.byName.gt("Lovelace",
     * "Ada")` matches Lovelace Byron and Turing Alan. SQLite reads the index for it, so it finds the next page after a
     * document quickly.
     */
    public fun FieldIndex.gt(vararg values: Any): Where = compare(Comparison.Gt, values)

    /** As [gt], also matching [values] themselves. */
    public fun FieldIndex.gte(vararg values: Any): Where = compare(Comparison.Gte, values)

    /** The index's fields, taken together in order, come before [values], one per field. */
    public fun FieldIndex.lt(vararg values: Any): Where = compare(Comparison.Lt, values)

    /** As [lt], also matching [values] themselves. */
    public fun FieldIndex.lte(vararg values: Any): Where = compare(Comparison.Lte, values)

    // A partial index's own condition holds too, as it does for its lookups.
    private fun FieldIndex.compare(comparison: Comparison, values: Array<out Any>): Where {
        val row = sqlValues(values)
        val compared = if (paths.size == 1) {
            compare(paths.single(), comparison, row.single())
        } else {
            Where.CompareRow(paths, comparison, row)
        }
        return where?.let { Where.And(compared, it) } ?: compared
    }

    private fun compare(field: String, comparison: Comparison, value: SqlValue): Where =
        compare(Path(field), comparison, value)
}

internal enum class Comparison(val sql: String) {
    Eq("="), Neq("!="), Gt(">"), Gte(">="), Lt("<"), Lte("<="), Like("LIKE"),
}

internal fun where(block: Condition): Where = WhereScope().block()

/**
 * [path] compared with [value]. Every condition is built here or in [isIn], so an id compared with a UUID becomes a
 * condition on the table's key, [Where.Key]; any other id value, such as text that is no UUID, stays a field
 * condition and matches as it always would.
 */
internal fun compare(path: Path, comparison: Comparison, value: SqlValue): Where {
    val id = path.idValue(value)
    return if (id != null && comparison != Comparison.Like) {
        Where.Key(comparison, id)
    } else {
        Where.Compare(path, comparison, value)
    }
}

/** [path] equals one of [values]; ids that are all UUIDs become [Where.KeyIn], as [compare] does. */
internal fun isIn(path: Path, values: List<SqlValue>): Where {
    if (values.isEmpty()) return Where.In(path, values)
    val ids = values.map { path.idValue(it) ?: return Where.In(path, values) }
    return Where.KeyIn(ids)
}

private fun Path.idValue(value: SqlValue): Uuid? =
    if (field == ID_FIELD && value is SqlValue.Text) value.value.canonicalUuidOrNull() else null

/** A finite [Double] as a SQL real; `NaN` and infinities are rejected. */
internal val Double.sqlReal: SqlValue
    get() {
        require(isFinite()) { "Unsupported number: $this, SQLite has no NaN or infinity" }
        return SqlValue.Real(this)
    }

private val Long.sqlInteger: SqlValue get() = SqlValue.Integer(this)

private val List<SqlValue>.jsonArray: SqlValue
    get() = SqlValue.Text(
        JsonArray(
            map {
                when (it) {
                    is SqlValue.Text -> JsonPrimitive(it.value)
                    is SqlValue.Integer -> JsonPrimitive(it.value)
                    is SqlValue.Real -> JsonPrimitive(it.value)
                    SqlValue.Null -> JsonNull
                    is SqlValue.Blob -> error("Where.In takes no blobs")
                }
            },
        ).toString(),
    )

/** The condition as SQL; with [inline] its values are literals instead of parameters. */
internal fun Where.render(inline: Boolean = false): Fragment {
    fun value(value: SqlValue): Fragment = if (inline) Fragment(value.literal) else Fragment("?", listOf(value))

    return when (this) {
        is Where.Compare -> Fragment("${path.sql} ${comparison.sql} ") + value(value)

        is Where.CompareRow ->
            Fragment(paths.joinToString(", ", prefix = "(", postfix = ") ${comparison.sql} ") { it.sql }) +
                values.map(::value).joinToFragment(", ", prefix = "(", postfix = ")")

        is Where.Key -> Fragment("$ID_COLUMN ${comparison.sql} ") + value(SqlValue.Blob(value.toByteArray()))

        is Where.KeyIn ->
            if (inline) {
                values.map { value(SqlValue.Blob(it.toByteArray())) }
                    .joinToFragment(", ", prefix = "$ID_COLUMN IN (", postfix = ")")
            } else {
                // One parameter however many ids, as for [Where.In].
                val hex = values.joinToString(",", "[", "]") { "\"${it.toHexString()}\"" }
                Fragment("$ID_COLUMN IN (SELECT unhex(value) FROM json_each(?))", listOf(SqlValue.Text(hex)))
            }

        is Where.In ->
            when {
                values.isEmpty() -> Fragment("0 = 1")
                inline -> values.map(::value).joinToFragment(", ", prefix = "${path.sql} IN (", postfix = ")")
                // One parameter however many values, so SQLite's limit on parameters never applies.
                else -> Fragment("${path.sql} IN (SELECT value FROM json_each(?))", listOf(values.jsonArray))
            }

        is Where.IsNull -> Fragment("${path.sql} IS NULL")

        is Where.IsNotNull -> Fragment("${path.sql} IS NOT NULL")

        is Where.Contains ->
            Fragment("EXISTS (SELECT 1 FROM json_each($DATA_COLUMN, '${path.jsonPath}') WHERE value = ") +
                value(value) + ")"

        is Where.And -> Fragment("(") + left.render(inline) + " AND " + right.render(inline) + ")"

        is Where.Or -> Fragment("(") + left.render(inline) + " OR " + right.render(inline) + ")"

        is Where.Not -> Fragment("NOT (") + condition.render(inline) + ")"
    }
}
