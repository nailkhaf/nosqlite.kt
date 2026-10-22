package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

internal object Queries {

    fun createTable(collection: Collection): Command<Unit> = command(
        """
            CREATE TABLE IF NOT EXISTS ${collection.table} (
                rowid INTEGER PRIMARY KEY,
                data BLOB NOT NULL
            ) STRICT;
        """.trimIndent(),
    )

    fun insert(collection: Collection): Statement<String, DocumentId> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid;",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "Insert into ${collection.name} returned no rowid" }
            DocumentId(getLong(0))
        },
    )

    fun selectById(collection: Collection): Statement<DocumentId, String?> = Statement(
        sql = "SELECT json(data) FROM ${collection.table} WHERE rowid = ?;",
        encode = { id -> bindLong(1, id.value) },
        decode = firstOrNull { getText(0) },
    )

    // RETURNING gives back a row only when one was deleted: androidx.sqlite has no changes().
    fun deleteById(collection: Collection): Statement<DocumentId, Boolean> = Statement(
        sql = "DELETE FROM ${collection.table} WHERE rowid = ? RETURNING rowid;",
        encode = { id -> bindLong(1, id.value) },
        decode = { step() },
    )
}

internal fun <T> Collection.decode(json: String, deserializer: DeserializationStrategy<T>, id: DocumentId?): T = try {
    Database.Json.decodeFromString(deserializer, json)
} catch (e: SerializationException) {
    throw NosqliteException.DecodingFailed(name, id, e)
}
