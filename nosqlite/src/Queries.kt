package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement

internal object Queries {

    fun createTable(collection: Collection<*>): Command<Unit> = command(
        """
            CREATE TABLE IF NOT EXISTS ${collection.table} (
                rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                data BLOB NOT NULL
            ) STRICT;
        """.trimIndent(),
    )

    /** The indexes nosqlite manages on the input table; others are left alone. */
    val indexNames: Statement<String, List<String>> = Statement(
        sql = "SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = ? AND name GLOB '$INDEX_PREFIX*';",
        encode = { table -> bindText(1, table) },
        decode = rows { getText(0) },
    )

    fun createIndex(index: Index): Command<Unit> = command(
        "CREATE ${if (index.isUnique) "UNIQUE " else ""}INDEX \"${index.name}\" " +
            "ON ${index.collection.table} ${index.definition};",
    )

    fun dropIndex(name: String): Command<Unit> = command("DROP INDEX \"$name\";")

    fun select(collection: Collection<*>, condition: Condition?, limit: Int, offset: Int): Statement<Unit, List<Row>> {
        val where = condition?.let { Fragment(" WHERE ") + it.render() } ?: Fragment.Empty
        val page = Fragment(
            " ORDER BY rowid LIMIT ? OFFSET ?;",
            listOf(SqlValue.Integer(limit.toLong()), SqlValue.Integer(offset.toLong())),
        )
        return (Fragment("SELECT rowid, json(data) FROM ${collection.table}") + where + page)
            .statement(rows { Row(DocumentId(getLong(0)), getText(1)) })
    }

    fun insert(collection: Collection<*>): Statement<String, DocumentId> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid;",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "Insert into ${collection.name} returned no rowid" }
            DocumentId(getLong(0))
        },
    )
}

internal class Row(val id: DocumentId, val json: String)
