package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteException

/**
 * A named set of JSON documents, stored in its own table. Declare one as an object and list it in `Database { }`:
 * ```
 * object Users : Collection("users")
 *
 * val db = Database("app.db") { +Users }
 * ```
 */
public abstract class Collection(public val name: String) {
    init {
        require(name.matches(Name)) { "Invalid collection name: '$name'" }
        require(!name.startsWith("sqlite_", ignoreCase = true)) { "Collection name is reserved by SQLite: '$name'" }
    }

    override fun toString(): String = "Collection($name)"

    // Collection names are SQL identifiers, so they are part of the SQL text, quoted; everything else is bound.
    internal val table: String get() = "\"$name\""

    private companion object {
        val Name = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * Runs [block] on the transaction's connection. Only declared collections have a table, so SQLite not knowing this
 * collection's table means it wasn't declared.
 */
context(_: ReadScope)
internal suspend fun <R> Collection.withConnection(block: suspend context(Connection) () -> R): R = try {
    context(connection()) { block() }
} catch (e: SQLiteException) {
    if (e.message?.contains("no such table: $name") == true) throw NosqliteException.CollectionNotDeclared(name, e)
    throw e
}
