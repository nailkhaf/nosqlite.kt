package io.github.nailkhaf.nosqlite

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlin.reflect.KProperty1

/**
 * A field inside documents of type [T] whose value is a [V], as the JSON keys leading to it.
 * Built from property references, nested with `/`:
 * ```
 * User::age
 * User::name / Name::last
 * ```
 * The keys are the properties' Kotlin names. A property renamed with `@SerialName` needs [path] with its serial name.
 */
public class Path<in T, out V> internal constructor(
    internal val names: List<String>,
    // Spelled out as serial names by hand, so they are taken as they are, never checked against a descriptor.
    internal val serialNames: Boolean = false,
) {
    init {
        require(names.isNotEmpty()) { "Empty path" }
        for (name in names) {
            require(name.isNotEmpty() && '"' !in name) { "Invalid path segment: '$name' in ${names.joinToString(".")}" }
        }
    }

    override fun equals(other: Any?): Boolean = other is Path<*, *> && names == other.names

    override fun hashCode(): Int = names.hashCode()

    override fun toString(): String = names.joinToString(".")
}

public fun <T, V> path(property: KProperty1<T, V>): Path<T, V> = Path(listOf(property.name))

/** The field at [serialPath], serial names separated by dots, for properties renamed with `@SerialName`. */
public fun <V> path(serialPath: String): Path<Any?, V> = Path(serialPath.split('.'), serialNames = true)

/** The field [next] inside this property's value; a nullable parent is fine. */
public operator fun <A, B : Any, C> KProperty1<A, B?>.div(next: KProperty1<B, C>): Path<A, C> =
    Path(listOf(name, next.name))

/** The field [next] inside this path's value; a nullable parent is fine. */
public operator fun <A, B : Any, C> Path<A, B?>.div(next: KProperty1<B, C>): Path<A, C> =
    Path(names + next.name, serialNames)

@PublishedApi
internal fun <V> KProperty1<*, V>.toPath(): Path<Any?, V> = Path(listOf(name))

/** The SQLite JSON path: `$.a.b`, with keys that aren't plain identifiers quoted. */
internal val Path<*, *>.jsonPath: String
    get() = names.joinToString(".", prefix = "$.") { if (it.matches(Identifier)) it else "\"$it\"" }

/** The SQL value at this path, `->>` so it compares, orders and indexes as an SQL value. */
internal val Path<*, *>.sql: String
    get() = "data ->> '${jsonPath.replace("'", "''")}'"

private val Identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Checks that [path] leads through elements of [descriptor], so a property renamed with `@SerialName` fails here
 * instead of matching nothing. Maps and polymorphic values can't be checked past their own key.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun checkPath(path: Path<*, *>, descriptor: SerialDescriptor) {
    if (path.serialNames) return
    var current = descriptor
    for ((i, name) in path.names.withIndex()) {
        current = current.unwrapInline()
        if (current.kind is PolymorphicKind || current.kind == StructureKind.MAP) return
        val index = current.getElementIndex(name)
        require(index != CompositeDecoder.UNKNOWN_NAME) {
            val parent = if (i ==
                0
            ) {
                descriptor.simpleName
            } else {
                "${current.simpleName} (at '${path.names.take(i).joinToString(".")}')"
            }
            val elements = List(current.elementsCount) { current.getElementName(it) }
            "Path '$path' doesn't match the serial names of ${descriptor.simpleName}: $parent has no element '$name'" +
                " (it has ${elements.joinToString(", ").ifEmpty { "none" }})." +
                " For a property renamed with @SerialName use path<V>(\"serial.name\")."
        }
        current = current.getElementDescriptor(index)
    }
}

private fun SerialDescriptor.unwrapInline(): SerialDescriptor =
    if (isInline && elementsCount == 1) getElementDescriptor(0).unwrapInline() else this

private val SerialDescriptor.simpleName: String
    get() = serialName.removeSuffix("?").substringAfterLast('.')
