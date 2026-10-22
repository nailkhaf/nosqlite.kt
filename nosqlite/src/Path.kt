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
    init {
        require(names.isNotEmpty()) { "Empty path" }
        for (name in names) {
            require(name.isNotEmpty() && '"' !in name) { "Invalid path segment: '$name' in ${names.joinToString(".")}" }
        }
    }

    override fun equals(other: Any?): Boolean = other is Path<*> && names == other.names

    override fun hashCode(): Int = names.hashCode()

    override fun toString(): String = names.joinToString(".")
}

public fun <V> path(property: KProperty1<*, V>): Path<V> = Path(listOf(property.name))

/** The field at [serialPath], serial names separated by dots, for properties renamed with `@SerialName`. */
public fun <V> path(serialPath: String): Path<V> = Path(serialPath.split('.'))

/** The field [next] inside this property's value; a nullable parent is fine. */
public operator fun <B : Any, C> KProperty1<*, B?>.div(next: KProperty1<B, C>): Path<C> =
    Path(listOf(name, next.name))

/** The field [next] inside this path's value; a nullable parent is fine. */
public operator fun <B : Any, C> Path<B?>.div(next: KProperty1<B, C>): Path<C> =
    Path(names + next.name)

@PublishedApi
internal fun <V> KProperty1<*, V>.toPath(): Path<V> = Path(listOf(name))

/** The SQLite JSON path: `$.a.b`, with keys that aren't plain identifiers quoted. */
internal val Path<*>.jsonPath: String
    get() = names.joinToString(".", prefix = "$.") { if (it.matches(Identifier)) it else "\"$it\"" }

/** The SQL value at this path, `->>` so it compares, orders and indexes as an SQL value. */
internal val Path<*>.sql: String
    get() = "data ->> '${jsonPath.replace("'", "''")}'"

private val Identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
