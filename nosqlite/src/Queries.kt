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
            .statement(rows(row))
    }

    fun update(collection: Collection<*>, json: String, condition: Condition): Statement<Unit, List<Row>> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb(?) WHERE ", listOf(SqlValue.Text(json))) +
            condition.render() + RETURNING).statement(rows(row))

    fun patch(collection: Collection<*>, patch: String, condition: Condition): Statement<Unit, List<Row>> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb_patch(data, ?) WHERE ", listOf(SqlValue.Text(patch))) +
            condition.render() + RETURNING).statement(rows(row))

    fun delete(collection: Collection<*>, condition: Condition): Statement<Unit, List<Row>> =
        (Fragment("DELETE FROM ${collection.table} WHERE ") + condition.render() + RETURNING).statement(rows(row))

    fun insert(collection: Collection<*>): Statement<String, DocumentId> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid;",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "Insert into ${collection.name} returned no rowid" }
            DocumentId(getLong(0))
        },
    )
}

private const val RETURNING = " RETURNING rowid, json(data);"

private val row: SQLiteStatement.() -> Row = { Row(DocumentId(getLong(0)), getText(1)) }

internal class Row(val id: DocumentId, val json: String)
