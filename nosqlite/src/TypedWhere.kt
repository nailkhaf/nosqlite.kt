package io.github.nailkhaf.nosqlite

import kotlin.reflect.KProperty1

context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.eq(value: V): Where = compare(Comparison.Eq, value)

context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.neq(value: V): Where = compare(Comparison.Neq, value)

context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.gt(value: V): Where = compare(Comparison.Gt, value)

context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.gte(value: V): Where = compare(Comparison.Gte, value)

context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.lt(value: V): Where = compare(Comparison.Lt, value)

context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.lte(value: V): Where = compare(Comparison.Lte, value)

/** The field equals one of [values]; none matches nothing. */
context(_: WhereScope)
public infix fun <V : Comparable<V>> Field<*, V?>.isIn(values: Iterable<V>): Where =
    Where.In(Path(path), values.map(::sqlValue))

/** The array field has an element equal to [value]. */
context(_: WhereScope)
public infix fun <E> Field<*, Iterable<E>?>.contains(value: E): Where = Where.Contains(Path(path), sqlValue(value))

/** The field is null or missing. */
context(_: WhereScope)
public fun Field<*, *>.isNull(): Where = Where.IsNull(Path(path))

/** The field is present and not null. */
context(_: WhereScope)
public fun Field<*, *>.isNotNull(): Where = Where.IsNotNull(Path(path))

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.eq(value: V): Where = field eq value

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.neq(value: V): Where = field neq value

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.gt(value: V): Where = field gt value

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.gte(value: V): Where = field gte value

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.lt(value: V): Where = field lt value

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.lte(value: V): Where = field lte value

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.isIn(values: Iterable<V>): Where = field isIn values

context(_: WhereScope)
public infix fun <E> KProperty1<*, Iterable<E>?>.contains(value: E): Where = field contains value

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
