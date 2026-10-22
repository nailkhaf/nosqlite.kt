package io.github.nailkhaf.nosqlite

/** A sort key: a field and its direction, made by [QueryScope.asc] and [QueryScope.desc]. */
public class Order internal constructor(internal val path: Path, internal val descending: Boolean) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"
}
