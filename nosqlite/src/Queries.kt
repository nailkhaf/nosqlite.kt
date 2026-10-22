package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.SerializationException

internal object Queries {

    fun createTable(collection: Collection<*>): Command<Unit> = command(
        """
            CREATE TABLE IF NOT EXISTS ${collection.table} (
                rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                data BLOB NOT NULL
            ) STRICT;
        """.trimIndent(),
    )

    val indexNames: Statement<String, List<String>> = Statement(
        sql = "SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = ?;",
        encode = { table -> bindText(1, table) },
        decode = rows { getText(0) },
    )

    fun createIndex(index: Index<*>): Command<Unit> = command(
        "CREATE ${if (index.isUnique) "UNIQUE " else ""}INDEX \"${index.name}\" " +
            "ON ${index.collection.table} ${index.definition};",
    )

    fun dropIndex(name: String): Command<Unit> = command("DROP INDEX \"$name\";")

    fun selectFirst(collection: Collection<*>, condition: Condition): Statement<Unit, Row?> =
        (Fragment("SELECT rowid, json(data) FROM ${collection.table} WHERE ") + condition.render() + " LIMIT 1;")
            .statement(firstOrNull { Row(DocumentId(getLong(0)), getText(1)) })

    fun insert(collection: Collection<*>): Statement<String, DocumentId> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid;",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "Insert into ${collection.name} returned no rowid" }
            DocumentId(getLong(0))
        },
    )

    fun selectById(collection: Collection<*>): Statement<DocumentId, String?> = Statement(
        sql = "SELECT json(data) FROM ${collection.table} WHERE rowid = ?;",
        encode = { id -> bindLong(1, id.value) },
        decode = firstOrNull { getText(0) },
    )

    // RETURNING gives back a row only when one was deleted: androidx.sqlite has no changes().
    fun deleteById(collection: Collection<*>): Statement<DocumentId, Boolean> = Statement(
        sql = "DELETE FROM ${collection.table} WHERE rowid = ? RETURNING rowid;",
        encode = { id -> bindLong(1, id.value) },
        decode = { step() },
    )
}

internal class Row(val id: DocumentId, val json: String)

internal fun <T> Collection<T>.decode(row: Row): T = decode(row.json, row.id)

internal fun <T> Collection<T>.decode(json: String, id: DocumentId?): T = try {
    Database.Json.decodeFromString(serializer, json)
} catch (e: SerializationException) {
    throw NosqliteException.DecodingFailed(name, id, e)
}
