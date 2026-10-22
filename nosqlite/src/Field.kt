package io.github.nailkhaf.nosqlite

import kotlin.reflect.KProperty1

/**
 * A nested field of [T] holding [V], made from properties with `/`: `User::name / Name::last`. Keys are property
 * names, so a property renamed with `@SerialName` is named by a string instead.
 */
public class Field<T, out V> internal constructor(internal val keys: List<String>) {
    internal val path: String get() = keys.joinToString(".")

    override fun toString(): String = path
}

public operator fun <T, M, V> KProperty1<T, M?>.div(next: KProperty1<M, V>): Field<T, V> = Field(listOf(name, next.name))

public operator fun <T, M, V> Field<T, M?>.div(next: KProperty1<M, V>): Field<T, V> = Field(keys + next.name)

internal val <T, V> KProperty1<T, V>.field: Field<T, V> get() = Field(listOf(name))
