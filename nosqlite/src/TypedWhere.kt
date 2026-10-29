package io.github.nailkhaf.nosqlite

import kotlin.jvm.JvmName
import kotlin.reflect.KProperty1

// Comparisons bind an SQL scalar, the kind of value `data ->> '$.path'` yields: text, integer, real, or a boolean
// as 1 or 0. The overloads are the restriction. An enum, Float, Char, unsigned type, value class or other Kotlin
// type has none of these operators; the string-key form takes the SQL value it is stored as. A nullable property
// uses isNull and isNotNull, and passes a non-null value to the operators below.

context(_: WhereScope)
public infix fun Field<*, String?>.eq(value: String): Where = compare(Comparison.Eq, value)

context(_: WhereScope)
public infix fun Field<*, Int?>.eq(value: Int): Where = compare(Comparison.Eq, value)

context(_: WhereScope)
public infix fun Field<*, Long?>.eq(value: Long): Where = compare(Comparison.Eq, value)

context(_: WhereScope)
public infix fun Field<*, Double?>.eq(value: Double): Where = compare(Comparison.Eq, value)

context(_: WhereScope)
public infix fun Field<*, Boolean?>.eq(value: Boolean): Where = compare(Comparison.Eq, value)

context(_: WhereScope)
public infix fun Field<*, String?>.neq(value: String): Where = compare(Comparison.Neq, value)

context(_: WhereScope)
public infix fun Field<*, Int?>.neq(value: Int): Where = compare(Comparison.Neq, value)

context(_: WhereScope)
public infix fun Field<*, Long?>.neq(value: Long): Where = compare(Comparison.Neq, value)

context(_: WhereScope)
public infix fun Field<*, Double?>.neq(value: Double): Where = compare(Comparison.Neq, value)

context(_: WhereScope)
public infix fun Field<*, Boolean?>.neq(value: Boolean): Where = compare(Comparison.Neq, value)

context(_: WhereScope)
public infix fun Field<*, String?>.gt(value: String): Where = compare(Comparison.Gt, value)

context(_: WhereScope)
public infix fun Field<*, Int?>.gt(value: Int): Where = compare(Comparison.Gt, value)

context(_: WhereScope)
public infix fun Field<*, Long?>.gt(value: Long): Where = compare(Comparison.Gt, value)

context(_: WhereScope)
public infix fun Field<*, Double?>.gt(value: Double): Where = compare(Comparison.Gt, value)

context(_: WhereScope)
public infix fun Field<*, Boolean?>.gt(value: Boolean): Where = compare(Comparison.Gt, value)

context(_: WhereScope)
public infix fun Field<*, String?>.gte(value: String): Where = compare(Comparison.Gte, value)

context(_: WhereScope)
public infix fun Field<*, Int?>.gte(value: Int): Where = compare(Comparison.Gte, value)

context(_: WhereScope)
public infix fun Field<*, Long?>.gte(value: Long): Where = compare(Comparison.Gte, value)

context(_: WhereScope)
public infix fun Field<*, Double?>.gte(value: Double): Where = compare(Comparison.Gte, value)

context(_: WhereScope)
public infix fun Field<*, Boolean?>.gte(value: Boolean): Where = compare(Comparison.Gte, value)

context(_: WhereScope)
public infix fun Field<*, String?>.lt(value: String): Where = compare(Comparison.Lt, value)

context(_: WhereScope)
public infix fun Field<*, Int?>.lt(value: Int): Where = compare(Comparison.Lt, value)

context(_: WhereScope)
public infix fun Field<*, Long?>.lt(value: Long): Where = compare(Comparison.Lt, value)

context(_: WhereScope)
public infix fun Field<*, Double?>.lt(value: Double): Where = compare(Comparison.Lt, value)

context(_: WhereScope)
public infix fun Field<*, Boolean?>.lt(value: Boolean): Where = compare(Comparison.Lt, value)

context(_: WhereScope)
public infix fun Field<*, String?>.lte(value: String): Where = compare(Comparison.Lte, value)

context(_: WhereScope)
public infix fun Field<*, Int?>.lte(value: Int): Where = compare(Comparison.Lte, value)

context(_: WhereScope)
public infix fun Field<*, Long?>.lte(value: Long): Where = compare(Comparison.Lte, value)

context(_: WhereScope)
public infix fun Field<*, Double?>.lte(value: Double): Where = compare(Comparison.Lte, value)

context(_: WhereScope)
public infix fun Field<*, Boolean?>.lte(value: Boolean): Where = compare(Comparison.Lte, value)

/** The text field matches [pattern]: `%` is any run of characters, `_` any one; ASCII letters ignore case. */
context(_: WhereScope)
public infix fun Field<*, String?>.like(pattern: String): Where = compare(Comparison.Like, pattern)

/** The field equals one of [values]; none matches nothing. */
context(_: WhereScope)
@JvmName("isInText")
public infix fun Field<*, String?>.isIn(values: Iterable<String>): Where = oneOf(values)

/** The field equals one of [values]; none matches nothing. */
context(_: WhereScope)
@JvmName("isInInt")
public infix fun Field<*, Int?>.isIn(values: Iterable<Int>): Where = oneOf(values)

/** The field equals one of [values]; none matches nothing. */
context(_: WhereScope)
@JvmName("isInLong")
public infix fun Field<*, Long?>.isIn(values: Iterable<Long>): Where = oneOf(values)

/** The field equals one of [values]; none matches nothing. */
context(_: WhereScope)
@JvmName("isInReal")
public infix fun Field<*, Double?>.isIn(values: Iterable<Double>): Where = oneOf(values)

/** The field equals one of [values]; none matches nothing. */
context(_: WhereScope)
@JvmName("isInBoolean")
public infix fun Field<*, Boolean?>.isIn(values: Iterable<Boolean>): Where = oneOf(values)

/** The array field has an element equal to [value]. */
context(_: WhereScope)
public infix fun Field<*, Iterable<String>?>.contains(value: String): Where = element(value)

/** The array field has an element equal to [value]. */
context(_: WhereScope)
public infix fun Field<*, Iterable<Int>?>.contains(value: Int): Where = element(value)

/** The array field has an element equal to [value]. */
context(_: WhereScope)
public infix fun Field<*, Iterable<Long>?>.contains(value: Long): Where = element(value)

/** The array field has an element equal to [value]. */
context(_: WhereScope)
public infix fun Field<*, Iterable<Double>?>.contains(value: Double): Where = element(value)

/** The array field has an element equal to [value]. */
context(_: WhereScope)
public infix fun Field<*, Iterable<Boolean>?>.contains(value: Boolean): Where = element(value)

/** The field is null or missing. */
context(_: WhereScope)
public fun Field<*, *>.isNull(): Where = Where.IsNull(Path(path))

/** The field is present and not null. */
context(_: WhereScope)
public fun Field<*, *>.isNotNull(): Where = Where.IsNotNull(Path(path))

context(_: WhereScope)
public infix fun KProperty1<*, String?>.eq(value: String): Where = field eq value

context(_: WhereScope)
public infix fun KProperty1<*, Int?>.eq(value: Int): Where = field eq value

context(_: WhereScope)
public infix fun KProperty1<*, Long?>.eq(value: Long): Where = field eq value

context(_: WhereScope)
public infix fun KProperty1<*, Double?>.eq(value: Double): Where = field eq value

context(_: WhereScope)
public infix fun KProperty1<*, Boolean?>.eq(value: Boolean): Where = field eq value

context(_: WhereScope)
public infix fun KProperty1<*, String?>.neq(value: String): Where = field neq value

context(_: WhereScope)
public infix fun KProperty1<*, Int?>.neq(value: Int): Where = field neq value

context(_: WhereScope)
public infix fun KProperty1<*, Long?>.neq(value: Long): Where = field neq value

context(_: WhereScope)
public infix fun KProperty1<*, Double?>.neq(value: Double): Where = field neq value

context(_: WhereScope)
public infix fun KProperty1<*, Boolean?>.neq(value: Boolean): Where = field neq value

context(_: WhereScope)
public infix fun KProperty1<*, String?>.gt(value: String): Where = field gt value

context(_: WhereScope)
public infix fun KProperty1<*, Int?>.gt(value: Int): Where = field gt value

context(_: WhereScope)
public infix fun KProperty1<*, Long?>.gt(value: Long): Where = field gt value

context(_: WhereScope)
public infix fun KProperty1<*, Double?>.gt(value: Double): Where = field gt value

context(_: WhereScope)
public infix fun KProperty1<*, Boolean?>.gt(value: Boolean): Where = field gt value

context(_: WhereScope)
public infix fun KProperty1<*, String?>.gte(value: String): Where = field gte value

context(_: WhereScope)
public infix fun KProperty1<*, Int?>.gte(value: Int): Where = field gte value

context(_: WhereScope)
public infix fun KProperty1<*, Long?>.gte(value: Long): Where = field gte value

context(_: WhereScope)
public infix fun KProperty1<*, Double?>.gte(value: Double): Where = field gte value

context(_: WhereScope)
public infix fun KProperty1<*, Boolean?>.gte(value: Boolean): Where = field gte value

context(_: WhereScope)
public infix fun KProperty1<*, String?>.lt(value: String): Where = field lt value

context(_: WhereScope)
public infix fun KProperty1<*, Int?>.lt(value: Int): Where = field lt value

context(_: WhereScope)
public infix fun KProperty1<*, Long?>.lt(value: Long): Where = field lt value

context(_: WhereScope)
public infix fun KProperty1<*, Double?>.lt(value: Double): Where = field lt value

context(_: WhereScope)
public infix fun KProperty1<*, Boolean?>.lt(value: Boolean): Where = field lt value

context(_: WhereScope)
public infix fun KProperty1<*, String?>.lte(value: String): Where = field lte value

context(_: WhereScope)
public infix fun KProperty1<*, Int?>.lte(value: Int): Where = field lte value

context(_: WhereScope)
public infix fun KProperty1<*, Long?>.lte(value: Long): Where = field lte value

context(_: WhereScope)
public infix fun KProperty1<*, Double?>.lte(value: Double): Where = field lte value

context(_: WhereScope)
public infix fun KProperty1<*, Boolean?>.lte(value: Boolean): Where = field lte value

context(_: WhereScope)
public infix fun KProperty1<*, String?>.like(pattern: String): Where = field like pattern

context(_: WhereScope)
@JvmName("isInTextProperty")
public infix fun KProperty1<*, String?>.isIn(values: Iterable<String>): Where = field isIn values

context(_: WhereScope)
@JvmName("isInIntProperty")
public infix fun KProperty1<*, Int?>.isIn(values: Iterable<Int>): Where = field isIn values

context(_: WhereScope)
@JvmName("isInLongProperty")
public infix fun KProperty1<*, Long?>.isIn(values: Iterable<Long>): Where = field isIn values

context(_: WhereScope)
@JvmName("isInRealProperty")
public infix fun KProperty1<*, Double?>.isIn(values: Iterable<Double>): Where = field isIn values

context(_: WhereScope)
@JvmName("isInBooleanProperty")
public infix fun KProperty1<*, Boolean?>.isIn(values: Iterable<Boolean>): Where = field isIn values

context(_: WhereScope)
public infix fun KProperty1<*, Iterable<String>?>.contains(value: String): Where = field contains value

context(_: WhereScope)
public infix fun KProperty1<*, Iterable<Int>?>.contains(value: Int): Where = field contains value

context(_: WhereScope)
public infix fun KProperty1<*, Iterable<Long>?>.contains(value: Long): Where = field contains value

context(_: WhereScope)
public infix fun KProperty1<*, Iterable<Double>?>.contains(value: Double): Where = field contains value

context(_: WhereScope)
public infix fun KProperty1<*, Iterable<Boolean>?>.contains(value: Boolean): Where = field contains value

context(_: WhereScope)
public fun KProperty1<*, *>.isNull(): Where = field.isNull()

context(_: WhereScope)
public fun KProperty1<*, *>.isNotNull(): Where = field.isNotNull()

/** Smallest first; null and missing values come first. */
context(scope: OrderScope)
public val Field<*, *>.asc: Unit get() = scope.add(Order(Path(path), descending = false))

/** Largest first; null and missing values come last. */
context(scope: OrderScope)
public val Field<*, *>.desc: Unit get() = scope.add(Order(Path(path), descending = true))

context(_: OrderScope)
public val KProperty1<*, *>.asc: Unit get() = field.asc

context(_: OrderScope)
public val KProperty1<*, *>.desc: Unit get() = field.desc

private fun Field<*, *>.compare(comparison: Comparison, value: Any): Where =
    Where.Compare(Path(path), comparison, sqlValue(value))

private fun Field<*, *>.oneOf(values: Iterable<*>): Where = Where.In(Path(path), values.map(::sqlValue))

private fun Field<*, *>.element(value: Any): Where = Where.Contains(Path(path), sqlValue(value))
