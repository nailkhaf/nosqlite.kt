package io.github.nailkhaf.nosqlite

/**
 * A field inside documents, as the JSON keys leading to it: `"age"`, or `"name.last"` for a nested object.
 * The keys are the ones stored, so a property renamed with `@SerialName` goes by its serial name.
 */
internal class Path(val field: String) {
    val names: List<String> = field.split('.')

    // Paths are spelled out in the SQL text, since expression indexes only match a literal path.
    init {
        require(names.all { it.matches(Identifier) }) { "Invalid field '$field': keys must be identifiers" }
    }

    override fun toString(): String = field
}

/** The SQLite JSON path: `$.a.b`. */
internal val Path.jsonPath: String
    get() = "$.$field"

/** The SQL value at this path, `->>` so it compares, orders and indexes as an SQL value. */
internal val Path.sql: String
    get() = "data ->> '$jsonPath'"

private val Identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
