package io.github.nailkhaf.nosqlite

/** A sort key for [list]: a field and its direction. Ties always fall back to insertion order. */
public class Order private constructor(internal val path: Path, internal val descending: Boolean) {
    override fun toString(): String = "$path ${if (descending) "desc" else "asc"}"

    public companion object {
        /** Smallest first; null and missing values come first. */
        public fun asc(field: String): Order = Order(Path(field), descending = false)

        /** Largest first; null and missing values come last. */
        public fun desc(field: String): Order = Order(Path(field), descending = true)
    }
}
