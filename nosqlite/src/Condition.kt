package io.github.nailkhaf.nosqlite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/** A filter on documents, built in [WhereScope]. */
public sealed class Condition {
    internal class Compare(val path: Path<*, *>, val comparison: Comparison, val value: SqlValue) : Condition()
    internal class In(val path: Path<*, *>, val values: List<SqlValue>) : Condition()
    internal class IsNull(val path: Path<*, *>) : Condition()
    internal class Contains(val path: Path<*, *>, val value: SqlValue) : Condition()
    internal class And(val left: Condition, val right: Condition) : Condition()
    internal class Or(val left: Condition, val right: Condition) : Condition()
    internal class Not(val condition: Condition) : Condition()

    override fun toString(): String = render(inline = true).sql
}

/** Marks the query builders, so an outer builder's functions don't leak into an inner block. */
@DslMarker
public annotation class QueryDsl

/**
 * Builds a [Condition] from typed fields. Values are encoded with their serializers, so enums, value classes and
 * `@Serializable` scalars compare as they are stored:
 * ```
 * (User::age gte 18) and (User::tags contains "kotlin")
 * ```
 * Every operator takes a property reference or a [Path].
 */
@QueryDsl
public class WhereScope @PublishedApi internal constructor() {
    /** The field equals [value]; `eq null` matches a null or missing field. */
    public inline infix fun <reified V> Path<*, V>.eq(value: V): Condition =
        compare(this, Comparison.Eq, serializer<V>(), value)

    /** The field differs from [value]; `neq null` matches a present, non-null field. */
    public inline infix fun <reified V> Path<*, V>.neq(value: V): Condition =
        compare(this, Comparison.Neq, serializer<V>(), value)

    /** The field is greater than [value]. */
    public inline infix fun <reified V> Path<*, V>.gt(value: V): Condition =
        compare(this, Comparison.Gt, serializer<V>(), value)

    /** The field is greater than or equal to [value]. */
    public inline infix fun <reified V> Path<*, V>.gte(value: V): Condition =
        compare(this, Comparison.Gte, serializer<V>(), value)

    /** The field is less than [value]. */
    public inline infix fun <reified V> Path<*, V>.lt(value: V): Condition =
        compare(this, Comparison.Lt, serializer<V>(), value)

    /** The field is less than or equal to [value]. */
    public inline infix fun <reified V> Path<*, V>.lte(value: V): Condition =
        compare(this, Comparison.Lte, serializer<V>(), value)

    /** The field equals one of [values]; none matches nothing. */
    public inline infix fun <reified V> Path<*, V>.isIn(values: Iterable<V>): Condition =
        anyOf(this, serializer<V>(), values)

    /** The array field has an element equal to [value]. */
    public inline infix fun <reified E> Path<*, Iterable<E>?>.contains(value: E): Condition =
        element(this, serializer<E>(), value)

    /** The field is null or missing. */
    public fun Path<*, *>.isNull(): Condition = Condition.IsNull(this)

    /** [eq] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.eq(value: V): Condition = toPath() eq value

    /** [neq] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.neq(value: V): Condition = toPath() neq value

    /** [gt] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.gt(value: V): Condition = toPath() gt value

    /** [gte] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.gte(value: V): Condition = toPath() gte value

    /** [lt] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.lt(value: V): Condition = toPath() lt value

    /** [lte] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.lte(value: V): Condition = toPath() lte value

    /** [isIn] on this property. */
    public inline infix fun <reified V> KProperty1<*, V>.isIn(values: Iterable<V>): Condition = toPath() isIn values

    /** [contains] on this array property. */
    public inline infix fun <reified E> KProperty1<*, Iterable<E>?>.contains(value: E): Condition =
        toPath() contains value

    /** [isNull] on this property. */
    public fun KProperty1<*, *>.isNull(): Condition = toPath().isNull()

    /** Both conditions match. */
    public infix fun Condition.and(other: Condition): Condition = Condition.And(this, other)

    /** Either condition matches. */
    public infix fun Condition.or(other: Condition): Condition = Condition.Or(this, other)

    /** [condition] does not match. */
    public fun not(condition: Condition): Condition = Condition.Not(condition)

    @PublishedApi
    internal fun <V> compare(
        path: Path<*, *>,
        comparison: Comparison,
        serializer: KSerializer<V>,
        value: V,
    ): Condition = Condition.Compare(path, comparison, sqlValue(serializer, value))

    @PublishedApi
    internal fun <V> anyOf(path: Path<*, *>, serializer: KSerializer<V>, values: Iterable<V>): Condition =
        Condition.In(path, values.map { sqlValue(serializer, it) })

    @PublishedApi
    internal fun <E> element(path: Path<*, *>, serializer: KSerializer<E>, value: E): Condition =
        Condition.Contains(path, sqlValue(serializer, value))
}

/** A comparison operator and its SQL. */
@PublishedApi
internal enum class Comparison(val sql: String) { Eq("="), Neq("!="), Gt(">"), Gte(">="), Lt("<"), Lte("<=") }

@PublishedApi
internal fun where(block: WhereScope.() -> Condition): Condition = WhereScope().block()

internal inline fun <reified V> sqlValue(value: V): SqlValue = sqlValue(serializer<V>(), value)

/** [value] as it is stored: its JSON scalar, a boolean as 0 or 1 like `->>` gives it back. */
internal fun <V> sqlValue(serializer: SerializationStrategy<V>, value: V): SqlValue =
    when (val json = Database.Json.encodeToJsonElement(serializer, value)) {
        JsonNull -> SqlValue.Null

        is JsonPrimitive -> when {
            json.isString -> SqlValue.Text(json.content)
            json.booleanOrNull != null -> SqlValue.Integer(if (json.booleanOrNull!!) 1 else 0)
            json.longOrNull != null -> SqlValue.Integer(json.longOrNull!!)
            else -> SqlValue.Real(requireNotNull(json.doubleOrNull) { "Not a number: $json" })
        }

        else -> throw IllegalArgumentException(
            "Only scalars can be compared, not ${serializer.descriptor.serialName}: $json",
        )
    }

/** The condition as SQL; with [inline] its values are literals instead of parameters. */
internal fun Condition.render(inline: Boolean = false): Fragment {
    fun value(value: SqlValue): Fragment = if (inline) Fragment(value.literal) else Fragment("?", listOf(value))

    return when (this) {
        is Condition.Compare -> when {
            value == SqlValue.Null && comparison == Comparison.Eq -> Fragment("${path.sql} IS NULL")
            value == SqlValue.Null && comparison == Comparison.Neq -> Fragment("${path.sql} IS NOT NULL")
            else -> Fragment("${path.sql} ${comparison.sql} ") + value(value)
        }

        is Condition.In ->
            if (values.isEmpty()) {
                Fragment("0 = 1")
            } else {
                values.map(::value).joinToFragment(", ", prefix = "${path.sql} IN (", postfix = ")")
            }

        is Condition.IsNull -> Fragment("${path.sql} IS NULL")

        is Condition.Contains ->
            Fragment("EXISTS (SELECT 1 FROM json_each(data, '${path.jsonPath.replace("'", "''")}') WHERE value = ") +
                value(value) + ")"

        is Condition.And -> Fragment("(") + left.render(inline) + " AND " + right.render(inline) + ")"

        is Condition.Or -> Fragment("(") + left.render(inline) + " OR " + right.render(inline) + ")"

        is Condition.Not -> Fragment("NOT (") + condition.render(inline) + ")"
    }
}
