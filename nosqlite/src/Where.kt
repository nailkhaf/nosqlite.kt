package io.github.nailkhaf.nosqlite

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
 * Builds a [Where] on document fields, named by their JSON keys, compared with plain SQL values: text and
 * numbers, with [isTrue] and [isFalse] for booleans. Anything else is converted by the caller, as it is stored:
 * ```
 * ("age" gte 18) and ("name.last" eq "Lovelace") and ("role" eq "member") and ("tags" contains "kotlin")
 * ```
 */
@QueryDsl
public class WhereScope internal constructor() {
    public infix fun String.eq(value: String): Where = compare(this, Comparison.Eq, SqlValue.Text(value))

    public infix fun String.eq(value: Number): Where = compare(this, Comparison.Eq, value.sqlValue)

    public infix fun String.neq(value: String): Where = compare(this, Comparison.Neq, SqlValue.Text(value))

    public infix fun String.neq(value: Number): Where = compare(this, Comparison.Neq, value.sqlValue)

    public infix fun String.gt(value: String): Where = compare(this, Comparison.Gt, SqlValue.Text(value))

    public infix fun String.gt(value: Number): Where = compare(this, Comparison.Gt, value.sqlValue)

    public infix fun String.gte(value: String): Where = compare(this, Comparison.Gte, SqlValue.Text(value))

    public infix fun String.gte(value: Number): Where = compare(this, Comparison.Gte, value.sqlValue)

    public infix fun String.lt(value: String): Where = compare(this, Comparison.Lt, SqlValue.Text(value))

    public infix fun String.lt(value: Number): Where = compare(this, Comparison.Lt, value.sqlValue)

    public infix fun String.lte(value: String): Where = compare(this, Comparison.Lte, SqlValue.Text(value))

    public infix fun String.lte(value: Number): Where = compare(this, Comparison.Lte, value.sqlValue)

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInText")
    public infix fun String.isIn(values: Iterable<String>): Where =
        Where.In(Path(this), values.map(SqlValue::Text))

    /** The field equals one of [values]; none matches nothing. */
    @JvmName("isInNumber")
    public infix fun String.isIn(values: Iterable<Number>): Where =
        Where.In(Path(this), values.map { it.sqlValue })

    /** The array field has an element equal to [value]. */
    public infix fun String.contains(value: String): Where = Where.Contains(Path(this), SqlValue.Text(value))

    public infix fun String.contains(value: Number): Where = Where.Contains(Path(this), value.sqlValue)

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

internal val Number.sqlValue: SqlValue
    get() = when (this) {
        is Int, is Long, is Short, is Byte -> SqlValue.Integer(toLong())
        is Double, is Float -> SqlValue.Real(toDouble())
        else -> throw IllegalArgumentException("Unsupported number: ${this::class.simpleName}")
    }

/** The condition as SQL; with [inline] its values are literals instead of parameters. */
internal fun Where.render(inline: Boolean = false): Fragment {
    fun value(value: SqlValue): Fragment = if (inline) Fragment(value.literal) else Fragment("?", listOf(value))

    return when (this) {
        is Where.Compare -> Fragment("${path.sql} ${comparison.sql} ") + value(value)

        is Where.In ->
            if (values.isEmpty()) {
                Fragment("0 = 1")
            } else {
                values.map(::value).joinToFragment(", ", prefix = "${path.sql} IN (", postfix = ")")
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
