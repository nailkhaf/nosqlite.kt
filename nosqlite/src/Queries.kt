package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer

internal object Queries {

    fun createTable(collection: BaseCollection): Command<Unit> = command(
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

    fun <T> select(collection: BaseCollection, serializer: KSerializer<T>, query: Query): Statement<Unit, List<T>> =
        selectSql(collection, query).statement(rows { collection.decode(serializer, this) })

    fun selectSql(collection: BaseCollection, query: Query): Fragment {
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

    fun count(collection: BaseCollection, condition: Where?): Statement<Unit, Long> =
        (Fragment("SELECT count(*) FROM ${collection.table}") + where(condition) + ";").statement {
            check(step()) { "count(*) returned no row" }
            getLong(0)
        }

    fun selectRows(collection: BaseCollection, condition: Where): Statement<Unit, List<Written>> =
        (Fragment("SELECT rowid, json(data) FROM ${collection.table} WHERE ") + condition.render() + ";")
            .statement(rows { written() })

    fun update(collection: BaseCollection, patch: String, condition: Where): Statement<Unit, List<Written>> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb_patch(data, ?) WHERE ", listOf(SqlValue.Text(patch))) +
            condition.render() + RETURNING_ROW).statement(rows { written() })

    fun replace(collection: BaseCollection, json: String, condition: Where): Statement<Unit, List<Written>> =
        (Fragment("UPDATE ${collection.table} SET data = jsonb(?) WHERE ", listOf(SqlValue.Text(json))) +
            condition.render() + RETURNING_ROW).statement(rows { written() })

    fun delete(collection: BaseCollection, condition: Where): Statement<Unit, List<Written>> =
        (Fragment("DELETE FROM ${collection.table} WHERE ") + condition.render() + RETURNING_ROW)
            .statement(rows { written() })

    /** Inserts the input JSON, or applies it as a merge patch to the document it conflicts with in [index]. */
    fun upsert(index: Index): Statement<String, Written> = Statement(
        sql = "INSERT INTO ${index.collection.table} (data) VALUES (jsonb(?1)) " +
            "ON CONFLICT ${index.definition} DO UPDATE SET data = jsonb_patch(data, ?1) RETURNING rowid, json(data);",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "upsert returned no row" }
            written()
        },
    )

    fun insert(collection: BaseCollection): Statement<String, Written> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid, json(data);",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "insert returned no row" }
            written()
        },
    )
}

// Identifiers can't be bound, so they are quoted into the SQL text with their own quotes doubled.
internal fun identifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

private fun where(condition: Where?): Fragment =
    condition?.let { Fragment(" WHERE ") + it.render() } ?: Fragment.Empty

private const val RETURNING_ROW = " RETURNING rowid, json(data);"

private fun SQLiteStatement.written(): Written = Written(getLong(0), getText(1))
