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

    /**
     * Like [update], and returns the primary-key value at [jsonPath] after the write. A patch that would change that
     * key leaves the row untouched and comes back [Kept.rejected]; the caller rolls the statement back by throwing.
     */
    fun updateKeeping(collection: Collection, json: String, condition: Where, jsonPath: String): Statement<Unit, Kept?> {
        val set = Fragment(
            "UPDATE ${collection.table} SET data = CASE " +
                "WHEN json_type(?, ?) IS NULL OR json_extract(data, ?) = json_extract(?, ?) " +
                "THEN jsonb_patch(data, ?) ELSE data END WHERE ",
            listOf(json, jsonPath, jsonPath, json, jsonPath, json).texts(),
        )
        val returning = Fragment(
            " RETURNING ${keyLiteral()}, " +
                "CASE WHEN json_type(?, ?) IS NOT NULL AND json_extract(data, ?) IS NOT json_extract(?, ?) " +
                "THEN 1 ELSE 0 END;",
            listOf(jsonPath, jsonPath, json, jsonPath, jsonPath, json, jsonPath).texts(),
        )
        return (set + condition.render() + returning).statement(kept)
    }

    /** Like [replace], with the same primary-key result as [updateKeeping]. The new document must carry the key. */
    fun replaceKeeping(collection: Collection, json: String, condition: Where, jsonPath: String): Statement<Unit, Kept?> {
        val set = Fragment(
            "UPDATE ${collection.table} SET data = CASE " +
                "WHEN json_extract(data, ?) = json_extract(?, ?) THEN jsonb(?) ELSE data END WHERE ",
            listOf(jsonPath, json, jsonPath, json).texts(),
        )
        val returning = Fragment(
            " RETURNING ${keyLiteral()}, CASE WHEN json_extract(data, ?) IS NOT json_extract(?, ?) THEN 1 ELSE 0 END;",
            listOf(jsonPath, jsonPath, jsonPath, json, jsonPath).texts(),
        )
        return (set + condition.render() + returning).statement(kept)
    }

    /** Deletes the one matching document and returns its primary-key value at [jsonPath], as JSON text. */
    fun deleteReturning(collection: Collection, condition: Where, jsonPath: String): Statement<Unit, Kept?> =
        (Fragment("DELETE FROM ${collection.table} WHERE ") + condition.render() +
            Fragment(" RETURNING ${keyLiteral()}, 0;", listOf(jsonPath, jsonPath).texts())).statement(kept)

    /**
     * Like [upsert], and returns the primary-key value at [jsonPath]. A conflicting patch that would change the key
     * leaves the row untouched and comes back [Kept.rejected].
     */
    fun upsertKeeping(index: Index, json: String, jsonPath: String): Statement<Unit, Kept> = Statement(
        sql = "INSERT INTO ${index.collection.table} (data) VALUES (jsonb(?)) " +
            "ON CONFLICT ${index.definition} DO UPDATE SET data = CASE " +
            "WHEN json_type(?, ?) IS NULL OR json_extract(data, ?) = json_extract(?, ?) " +
            "THEN jsonb_patch(data, ?) ELSE data END " +
            "RETURNING ${keyLiteral()}, " +
            "CASE WHEN json_type(?, ?) IS NOT NULL AND json_extract(data, ?) IS NOT json_extract(?, ?) " +
            "THEN 1 ELSE 0 END;",
        encode = {
            bind(
                listOf(
                    json,
                    json, jsonPath, jsonPath, json, jsonPath, json,
                    jsonPath, jsonPath,
                    json, jsonPath, jsonPath, json, jsonPath,
                ).texts(),
            )
        },
        decode = {
            check(step()) { "upsert returned no row" }
            keptRow()
        },
    )

    /** A primary-key value returned as JSON text, and whether the write tried to change it. */
    class Kept(val keyJson: String?, val rejected: Boolean)

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

// `json_extract` turns JSON true into 1. Quote scalars, and spell booleans out, so the text parses back.
private fun keyLiteral(): String =
    "CASE json_type(data, ?) WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' WHEN 'null' THEN 'null' " +
        "ELSE json_quote(json_extract(data, ?)) END"

private fun List<String>.texts(): List<SqlValue> = map(SqlValue::Text)

private val kept: SQLiteStatement.() -> Queries.Kept? = { if (step()) keptRow() else null }

private fun SQLiteStatement.keptRow(): Queries.Kept = Queries.Kept(
    keyJson = if (isNull(0)) null else getText(0),
    rejected = getLong(1) != 0L,
)
