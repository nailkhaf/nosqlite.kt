package io.github.nailkhaf.nosqlite

import kotlin.reflect.KProperty1

/**
 * A field inside documents whose value is a [V], as the JSON keys leading to it.
 * Built from property references, nested with `/`:
 * ```
 * User::age
 * User::name / Name::last
 * ```
 * The keys are the properties' Kotlin names. For a property renamed with `@SerialName`, spell its path out with
 * [path] and the serial names.
 */
public class Path<out V> internal constructor(internal val names: List<String>) {
    // Paths are spelled out in the SQL text, since expression indexes only match a literal path.
    init {
        require(names.isNotEmpty() && names.all { it.matches(Identifier) }) {
            "Invalid path '${names.joinToString(".")}': keys must be identifiers"
        }
    }

    override fun equals(other: Any?): Boolean = other is Path<*> && names == other.names

    override fun hashCode(): Int = names.hashCode()

    override fun toString(): String = names.joinToString(".")
}

public fun <V> path(property: KProperty1<*, V>): Path<V> = Path(listOf(property.name))

/**
 * The field at [serialPath], serial names separated by dots, for properties renamed with `@SerialName`.
 * Its value type is unchecked: a comparison takes it from the value, `path("name.last") eq "Jones"`.
 */
public fun path(serialPath: String): Path<Nothing> = Path(serialPath.split('.'))

/** The field [next] inside this property's value; a nullable parent is fine. */
public operator fun <B : Any, C> KProperty1<*, B?>.div(next: KProperty1<B, C>): Path<C> =
    Path(listOf(name, next.name))

/** The field [next] inside this path's value; a nullable parent is fine. */
public operator fun <B : Any, C> Path<B?>.div(next: KProperty1<B, C>): Path<C> =
    Path(names + next.name)

@PublishedApi
internal fun <V> KProperty1<*, V>.toPath(): Path<V> = Path(listOf(name))

/** The SQLite JSON path: `$.a.b`. */
internal val Path<*>.jsonPath: String
    get() = names.joinToString(".", prefix = "$.")

/** The SQL value at this path, `->>` so it compares, orders and indexes as an SQL value. */
internal val Path<*>.sql: String
    get() = "data ->> '$jsonPath'"

private val Identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
