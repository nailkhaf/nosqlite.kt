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

/** The text field matches [pattern]: `%` is any run of characters, `_` any one; ASCII letters ignore case. */
context(_: WhereScope)
public infix fun Field<*, String?>.like(pattern: String): Where = compare(Comparison.Like, pattern)

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
public infix fun KProperty1<*, String?>.like(pattern: String): Where = field like pattern

context(_: WhereScope)
public infix fun <V : Comparable<V>> KProperty1<*, V?>.isIn(values: Iterable<V>): Where = field isIn values

context(_: WhereScope)
public infix fun <E> KProperty1<*, Iterable<E>?>.contains(value: E): Where = field contains value

context(_: WhereScope)
public fun KProperty1<*, *>.isNull(): Where = field.isNull()

context(_: WhereScope)
public fun KProperty1<*, *>.isNotNull(): Where = field.isNotNull()

/** Smallest first; null and missing values come first. */
context(_: QueryScope)
public fun asc(field: Field<*, *>): Order = Order(Path(field.path), descending = false)

/** Largest first; null and missing values come last. */
context(_: QueryScope)
public fun desc(field: Field<*, *>): Order = Order(Path(field.path), descending = true)

context(_: QueryScope)
public fun asc(property: KProperty1<*, *>): Order = asc(property.field)

context(_: QueryScope)
public fun desc(property: KProperty1<*, *>): Order = desc(property.field)

private fun Field<*, *>.compare(comparison: Comparison, value: Any): Where =
    Where.Compare(Path(path), comparison, sqlValue(value))
