package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer

internal object Queries {

    fun createTable(collection: Collection): Command<Unit> = command(
        """
            CREATE TABLE IF NOT EXISTS ${collection.table} (
                data BLOB NOT NULL
            ) STRICT;
        """.trimIndent(),
    )

    /** The columns [createTable] makes, as [tableColumns] lists them. */
    val TableColumns = listOf("data BLOB")

    /** The input table's columns: name, type, and whether it is the primary key. */
    val tableColumns: Statement<String, List<String>> = Statement(
        sql = "SELECT name || ' ' || upper(type) || iif(pk, ' PRIMARY KEY', '') FROM pragma_table_info(?) ORDER BY cid;",
        encode = { table -> bindText(1, table) },
        decode = rows { getText(0) },
    )

    fun dropTable(name: String): Command<Unit> = command("DROP TABLE IF EXISTS ${identifier(name)};")

    /** The indexes nosqlite manages on the input table; others are left alone. */
    val indexNames: Statement<String, List<String>> = Statement(
        sql = "SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = ? AND name GLOB '$INDEX_PREFIX*';",
        encode = { table -> bindText(1, table) },
        decode = rows { getText(0) },
    )

    fun createIndex(index: Index): Command<Unit> = command(
        "CREATE ${if (index.isUnique) "UNIQUE " else ""}INDEX ${identifier(index.name)} " +
            "ON ${index.collection.table} ${index.definition};",
    )

    fun dropIndex(name: String): Command<Unit> = command("DROP INDEX ${identifier(name)};")

    fun <T> select(collection: Collection, serializer: KSerializer<T>, query: Query): Statement<Unit, List<T>> =
        selectSql(collection, query).statement(rows { collection.decode(serializer, this) })

    fun selectSql(collection: Collection, query: Query): Fragment {
        // No implicit rowid: a descending key must be able to walk its index backwards.
        val order = query.orderBy
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = " ORDER BY ") { "${it.path.sql} ${if (it.descending) "DESC" else "ASC"}" }
            .orEmpty()
        val page = Fragment(
            "$order LIMIT ? OFFSET ?;",
            listOf(SqlValue.Integer(query.limit.toLong()), SqlValue.Integer(query.offset.toLong())),
        )
        return Fragment("SELECT json(data) FROM ${collection.table}") + where(query.condition) + page
    }

    fun count(collection: Collection, condition: Where?): Statement<Unit, Long> =
        (Fragment("SELECT count(*) FROM ${collection.table}") + where(condition) + ";").statement {
            check(step()) { "count(*) returned no row" }
            getLong(0)
        }

    fun update(collection: Collection, patch: String, condition: Where): Statement<Unit, Int> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb_patch(data, ?) WHERE ", listOf(SqlValue.Text(patch))) +
            condition.render() + RETURNING).statement(countRows)

    fun replace(collection: Collection, json: String, condition: Where): Statement<Unit, Int> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb(?) WHERE ", listOf(SqlValue.Text(json))) +
            condition.render() + RETURNING).statement(countRows)

    fun delete(collection: Collection, condition: Where): Statement<Unit, Int> =
        (Fragment("DELETE FROM ${collection.table} WHERE ") + condition.render() + RETURNING).statement(countRows)

    /** Inserts the input JSON, or applies it as a merge patch to the document it conflicts with in [index]. */
    fun upsert(index: Index): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${index.collection.table} (data) VALUES (jsonb(?1)) " +
            "ON CONFLICT ${index.definition} DO UPDATE SET data = jsonb_patch(data, ?1);",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    fun insert(collection: Collection): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?));",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )
}

// Identifiers can't be bound, so they are quoted into the SQL text with their own quotes doubled.
internal fun identifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

private fun where(condition: Where?): Fragment =
    condition?.let { Fragment(" WHERE ") + it.render() } ?: Fragment.Empty

// A row per document a write touched, counted as they come: androidx.sqlite has no changes().
private const val RETURNING = " RETURNING 1;"

private val countRows: SQLiteStatement.() -> Int = {
    var count = 0
    while (step()) count++
    count
}
