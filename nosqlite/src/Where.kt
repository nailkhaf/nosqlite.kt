package io.github.nailkhaf.nosqlite

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmName

/** A filter on documents, built in [WhereScope]. */
public sealed class Where {
    internal class Compare(val path: Path, val comparison: Comparison, val value: SqlValue) : Where()
    internal class In(val path: Path, val values: List<SqlValue>) : Where()
    internal class IsNull(val path: Path) : Where()
    internal class IsNotNull(val path: Path) : Where()
    internal class Contains(val path: Path, val value: SqlValue) : Where()
    internal class And(val left: Where, val right: Where) : Where()
    internal class Or(val left: Where, val right: Where) : Where()
    internal class Not(val condition: Where) : Where()

    override fun toString(): String = render(inline = true).sql
}

/** Marks the query builders, so an outer builder's functions don't leak into an inner block. */
@DslMarker
public annotation class QueryDsl

/**
 * Builds a [Where] on document fields, named by their JSON keys, compared with plain SQL values: [String],
 * [Int], [Long] and [Double], with [isTrue] and [isFalse] for booleans. A [Double] must be finite (`NaN` and
 * infinities are rejected). [Float], [Short], [Byte] and other [Number] types have no overload: widening a
 * [Float] with `toDouble()` is not exact. `isIn` of integer literals needs an element type, `listOf<Int>(18, 36)`
 * or `listOf(1L, 2L)`, because [Int] and [Long] both apply; `emptyList` needs one too. Anything else is converted
 * by the caller, as it is stored:
 * ```
 * ("age" gte 18) and ("name.last" eq "Lovelace") and ("role" eq "member") and ("tags" contains "kotlin")
 * ```
 */
@QueryDsl
public class WhereScope internal constructor() {
    public infix fun String.eq(value: String): Where = compare(this, Comparison.Eq, SqlValue.Text(value))

    public infix fun String.eq(value: Int): Where = compare(this, Comparison.Eq, value.sqlInteger)

    public infix fun String.eq(value: Long): Where = compare(this, Comparison.Eq, value.sqlInteger)

    public infix fun String.eq(value: Double): Where = compare(this, Comparison.Eq, value.sqlReal)

    public infix fun String.neq(value: String): Where = compare(this, Comparison.Neq, SqlValue.Text(value))

    public infix fun String.neq(value: Int): Where = compare(this, Comparison.Neq, value.sqlInteger)

    public infix fun String.neq(value: Long): Where = compare(this, Comparison.Neq, value.sqlInteger)

    public infix fun String.neq(value: Double): Where = compare(this, Comparison.Neq, value.sqlReal)

    public infix fun String.gt(value: String): Where = compare(this, Comparison.Gt, SqlValue.Text(value))

    public infix fun String.gt(value: Int): Where = compare(this, Comparison.Gt, value.sqlInteger)

    public infix fun String.gt(value: Long): Where = compare(this, Comparison.Gt, value.sqlInteger)

    public infix fun String.gt(value: Double): Where = compare(this, Comparison.Gt, value.sqlReal)

    public infix fun String.gte(value: String): Where = compare(this, Comparison.Gte, SqlValue.Text(value))

    public infix fun String.gte(value: Int): Where = compare(this, Comparison.Gte, value.sqlInteger)

    public infix fun String.gte(value: Long): Where = compare(this, Comparison.Gte, value.sqlInteger)

    public infix fun String.gte(value: Double): Where = compare(this, Comparison.Gte, value.sqlReal)

    public infix fun String.lt(value: String): Where = compare(this, Comparison.Lt, SqlValue.Text(value))

    public infix fun String.lt(value: Int): Where = compare(this, Comparison.Lt, value.sqlInteger)

    public infix fun String.lt(value: Long): Where = compare(this, Comparison.Lt, value.sqlInteger)

    public infix fun String.lt(value: Double): Where = compare(this, Comparison.Lt, value.sqlReal)

    public infix fun String.lte(value: String): Where = compare(this, Comparison.Lte, SqlValue.Text(value))

    public infix fun String.lte(value: Int): Where = compare(this, Comparison.Lte, value.sqlInteger)

    public infix fun String.lte(value: Long): Where = compare(this, Comparison.Lte, value.sqlInteger)

    public infix fun String.lte(value: Double): Where = compare(this, Comparison.Lte, value.sqlReal)

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInText")
    public infix fun String.isIn(values: Iterable<String>): Where =
        Where.In(Path(this), values.map(SqlValue::Text))

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInInt")
    public infix fun String.isIn(values: Iterable<Int>): Where =
        Where.In(Path(this), values.map { it.sqlInteger })

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInLong")
    public infix fun String.isIn(values: Iterable<Long>): Where =
        Where.In(Path(this), values.map { it.sqlInteger })

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInDouble")
    public infix fun String.isIn(values: Iterable<Double>): Where =
        Where.In(Path(this), values.map { it.sqlReal })

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: String): Where = Where.Contains(Path(this), SqlValue.Text(value))

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: Int): Where = Where.Contains(Path(this), value.sqlInteger)

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: Long): Where = Where.Contains(Path(this), value.sqlInteger)

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

    public infix fun Where.and(other: Where): Where = Where.And(this, other)

    public infix fun Where.or(other: Where): Where = Where.Or(this, other)

    public fun not(condition: Where): Where = Where.Not(condition)

    private fun compare(field: String, comparison: Comparison, value: SqlValue): Where =
        Where.Compare(Path(field), comparison, value)
}

internal enum class Comparison(val sql: String) {
    Eq("="), Neq("!="), Gt(">"), Gte(">="), Lt("<"), Lte("<="), Like("LIKE"),
}

internal fun where(block: WhereScope.() -> Where): Where = WhereScope().block()

/** A finite [Double] as a SQL real; `NaN` and infinities are rejected. */
internal val Double.sqlReal: SqlValue
    get() {
        require(isFinite()) { "Unsupported number: $this, SQLite has no NaN or infinity" }
        return SqlValue.Real(this)
    }

private val Int.sqlInteger: SqlValue get() = SqlValue.Integer(toLong())

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
                }
            },
        ).toString(),
    )

/** The condition as SQL; with [inline] its values are literals instead of parameters. */
internal fun Where.render(inline: Boolean = false): Fragment {
    fun value(value: SqlValue): Fragment = if (inline) Fragment(value.literal) else Fragment("?", listOf(value))

    return when (this) {
        is Where.Compare -> Fragment("${path.sql} ${comparison.sql} ") + value(value)

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
            Fragment("EXISTS (SELECT 1 FROM json_each(data, '${path.jsonPath}') WHERE value = ") +
                value(value) + ")"

        is Where.And -> Fragment("(") + left.render(inline) + " AND " + right.render(inline) + ")"

        is Where.Or -> Fragment("(") + left.render(inline) + " OR " + right.render(inline) + ")"

        is Where.Not -> Fragment("NOT (") + condition.render(inline) + ")"
    }
}
