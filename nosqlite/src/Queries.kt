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

    fun dropTable(name: String): Command<Unit> = command("DROP TABLE IF EXISTS \"$name\";")

    /** The indexes nosqlite manages on the input table; others are left alone. */
    val indexNames: Statement<String, List<String>> = Statement(
        sql = "SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = ? AND name GLOB '$INDEX_PREFIX*';",
        encode = { table -> bindText(1, table) },
        decode = rows { getText(0) },
    )

    fun createIndex(index: Index<*>): Command<Unit> = command(
        "CREATE ${if (index.isUnique) "UNIQUE " else ""}INDEX \"${index.name}\" " +
            "ON ${index.collection.table} ${index.definition};",
    )

    fun dropIndex(name: String): Command<Unit> = command("DROP INDEX \"$name\";")

    // rowid comes last, so documents with equal keys keep insertion order and pages never overlap.
    fun select(collection: Collection<*>, query: Query): Statement<Unit, List<Row>> =
        selectSql(collection, query).statement(rows(row))

    fun selectSql(collection: Collection<*>, query: Query): Fragment {
        val order = (query.orderBy.map { "${it.path.sql} ${if (it.descending) "DESC" else "ASC"}" } + "rowid")
            .joinToString(", ", prefix = " ORDER BY ")
        val page = Fragment(
            "$order LIMIT ? OFFSET ?;",
            listOf(SqlValue.Integer(query.limit.toLong()), SqlValue.Integer(query.offset.toLong())),
        )
        return Fragment("SELECT rowid, json(data) FROM ${collection.table}") + where(query.condition) + page
    }

    fun count(collection: Collection<*>, condition: Where?): Statement<Unit, Long> =
        (Fragment("SELECT count(*) FROM ${collection.table}") + where(condition) + ";").statement {
            check(step()) { "count(*) returned no row" }
            getLong(0)
        }

    fun update(collection: Collection<*>, json: String, condition: Where): Statement<Unit, Int> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb(?) WHERE ", listOf(SqlValue.Text(json))) +
            condition.render() + RETURNING).statement(countRows)

    fun patch(collection: Collection<*>, patch: String, condition: Where): Statement<Unit, Int> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb_patch(data, ?) WHERE ", listOf(SqlValue.Text(patch))) +
            condition.render() + RETURNING).statement(countRows)

    fun delete(collection: Collection<*>, condition: Where): Statement<Unit, Int> =
        (Fragment("DELETE FROM ${collection.table} WHERE ") + condition.render() + RETURNING).statement(countRows)

    /** Inserts the input JSON, or replaces the document it conflicts with in [index]. */
    fun upsert(index: Index<*>): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${index.collection.table} (data) VALUES (jsonb(?)) " +
            "ON CONFLICT ${index.definition} DO UPDATE SET data = excluded.data;",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    fun insert(collection: Collection<*>): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?));",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )
}

private fun where(condition: Where?): Fragment =
    condition?.let { Fragment(" WHERE ") + it.render() } ?: Fragment.Empty

// A row per document a write touched, counted as they come: androidx.sqlite has no changes().
private const val RETURNING = " RETURNING 1;"

private val countRows: SQLiteStatement.() -> Int = {
    var count = 0
    while (step()) count++
    count
}

internal class Row(val id: DocumentId, val json: String)

internal val row: SQLiteStatement.() -> Row = { Row(DocumentId(getLong(0)), getText(1)) }
