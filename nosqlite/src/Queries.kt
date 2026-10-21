package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

/** The SQL nosqlite runs, as statements that bind their input and decode their result. */
internal object Queries {

    fun createTable(collection: Collection): Command<Unit> = command(
        """
            CREATE TABLE IF NOT EXISTS ${collection.table} (
                rowid INTEGER PRIMARY KEY,
                data BLOB NOT NULL
            ) STRICT;
        """.trimIndent(),
    )

    /** Input is the collection name, result is the names of the indexes on its table. */
    val indexNames: Statement<String, List<String>> = Statement(
        sql = "SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = ? AND name NOT LIKE 'sqlite_%';",
        encode = { collection -> bindText(1, collection) },
        decode = rows { getText(0) },
    )

    fun createIndex(index: Index): Command<Unit> = command(
        "CREATE ${if (index.isUnique) "UNIQUE " else ""}INDEX IF NOT EXISTS \"${index.name}\" " +
            "ON ${index.collection.table} ${index.definition};",
    )

    fun dropIndex(name: String): Command<Unit> = command("DROP INDEX IF EXISTS \"$name\";")

    /** Up to [limit] documents matching [condition], with their ids. */
    fun selectWhere(collection: Collection, condition: Condition, limit: Int): Statement<Unit, List<Row>> =
        (Fragment("SELECT rowid, json(data) FROM ${collection.table} WHERE ") + condition.render() + " LIMIT $limit;")
            .statement(rows { Row(DocumentId(getLong(0)), getText(1)) })

    /** Input is the document's JSON text, stored as JSONB; the result is its new rowid. */
    fun insert(collection: Collection): Statement<String, DocumentId> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid;",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "Insert into ${collection.name} returned no rowid" }
            DocumentId(getLong(0))
        },
    )

    /** Input is the new document's JSON text, replacing every document matching [condition]; the result is how many. */
    fun update(collection: Collection, condition: Condition): Statement<String, Int> =
        changeWith(Fragment("UPDATE ${collection.table} SET data = jsonb(?) WHERE ") + condition.render())

    /** Input is a JSON merge patch applied to every document matching [condition]; the result is how many. */
    fun patch(collection: Collection, condition: Condition): Statement<String, Int> = changeWith(
        Fragment("UPDATE ${collection.table} SET data = jsonb_patch(data, jsonb(?)) WHERE ") + condition.render(),
    )

    /** Deletes every document matching [condition]; the result is how many. */
    fun delete(collection: Collection, condition: Condition): Statement<Unit, Int> =
        (Fragment("DELETE FROM ${collection.table} WHERE ") + condition.render() + " RETURNING rowid;")
            .statement { countRows() }

    // The JSON is bound first, ahead of the condition's own parameters.
    private fun changeWith(fragment: Fragment): Statement<String, Int> {
        val returning = fragment + " RETURNING rowid;"
        return Statement(
            sql = returning.sql,
            encode = { json ->
                bindText(1, json)
                bind(returning.params, startIndex = 2)
            },
            decode = { countRows() },
        )
    }
}

/** A stored document as a query reads it: its id and its JSON text. */
internal class Row(val id: DocumentId, val json: String)

/** Counts the rows a `RETURNING` clause gives back: the rows a statement changed. */
internal fun SQLiteStatement.countRows(): Int {
    var count = 0
    while (step()) count++
    return count
}

/** The document in [row] decoded with [deserializer]. */
internal fun <T> Collection.decode(row: Row, deserializer: DeserializationStrategy<T>): T =
    decode(row.json, deserializer, row.id)

/** [json] decoded with [deserializer]; a failure names the document. */
internal fun <T> Collection.decode(json: String, deserializer: DeserializationStrategy<T>, id: DocumentId?): T = try {
    Database.Json.decodeFromString(deserializer, json)
} catch (e: SerializationException) {
    throw NosqliteException.DecodingFailed(name, id, e)
}
