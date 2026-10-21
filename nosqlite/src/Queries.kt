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
            .statement(rows { row(keys = 0) })

    /** Input is the document's JSON text, stored as JSONB; the result is its new rowid. */
    fun insert(collection: Collection): Statement<String, DocumentId> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?)) RETURNING rowid;",
        encode = { json -> bindText(1, json) },
        decode = {
            check(step()) { "Insert into ${collection.name} returned no rowid" }
            DocumentId(getLong(0))
        },
    )

    /**
     * Up to [limit] documents [select] finds, in its order, after [after] when given; a negative [limit] means all.
     * Each row also carries its sort keys, so the last one can be the next page's cursor.
     */
    fun select(select: Select<*>, limit: Int, after: Cursor? = null): Statement<Unit, List<Row>> =
        selectFragment(select, limit, after).statement(rows { row(keys = select.orders.size) })

    fun selectFragment(select: Select<*>, limit: Int, after: Cursor? = null): Fragment {
        val keys = select.orders.joinToString("") { ", ${it.path.sql}" }
        val conditions = listOfNotNull(select.where?.render(), after?.let { keyset(select.orders, it) })
        val where = if (conditions.isEmpty()) Fragment.Empty else conditions.joinToFragment(" AND ", prefix = " WHERE ")
        return Fragment("SELECT rowid, json(data)$keys FROM ${select.collection.table}") + where +
            orderBy(select.orders) + Fragment(" LIMIT ?;", listOf(SqlValue.Integer(limit.toLong())))
    }

    /**
     * Rows strictly after [cursor] in [orders] then rowid: for some key, equal on every key before it and after on it.
     * NULL sorts first, so ascending after NULL is any value, and descending after NULL is nothing.
     */
    private fun keyset(orders: List<Order>, cursor: Cursor): Fragment {
        val alternatives = (0..orders.size).map { k ->
            val equal = (0 until k).map { Fragment("${orders[it].path.sql} IS ?", listOf(cursor.keys[it])) }
            val after = if (k == orders.size) {
                Fragment("rowid > ?", listOf(SqlValue.Integer(cursor.id.value)))
            } else {
                after(orders[k], cursor.keys[k])
            }
            (equal + after).joinToFragment(" AND ", prefix = "(", postfix = ")")
        }
        return alternatives.joinToFragment(" OR ", prefix = "(", postfix = ")")
    }

    private fun after(order: Order, key: SqlValue): Fragment {
        val sql = order.path.sql
        return when {
            key == SqlValue.Null && !order.descending -> Fragment("$sql IS NOT NULL")
            key == SqlValue.Null -> Fragment("0")
            !order.descending -> Fragment("$sql > ?", listOf(key))
            else -> Fragment("($sql < ? OR $sql IS NULL)", listOf(key))
        }
    }

    /** How many documents [select] finds. */
    fun count(select: Select<*>): Statement<Unit, Long> =
        (Fragment("SELECT count(*) FROM ${select.collection.table}") + where(select.where) + ";").statement {
            check(step()) { "count(*) returned no row" }
            getLong(0)
        }

    private fun where(condition: Condition?): Fragment =
        if (condition == null) Fragment.Empty else Fragment(" WHERE ") + condition.render()

    // rowid comes last, so equal keys keep insertion order and every order is total.
    private fun orderBy(orders: List<Order>): Fragment = Fragment(
        (orders.map { "${it.path.sql} ${if (it.descending) "DESC" else "ASC"}" } + "rowid ASC")
            .joinToString(", ", prefix = " ORDER BY "),
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

/** A stored document as a query reads it: its id, its JSON text and the values it sorts by. */
internal class Row(val id: DocumentId, val json: String, val keys: List<SqlValue> = emptyList())

/** Where a page ends: the last row's sort keys and id. */
internal class Cursor(val keys: List<SqlValue>, val id: DocumentId)

internal val Row.cursor: Cursor get() = Cursor(keys, id)

/** The current row of a `SELECT rowid, json(data), key...` statement. */
internal fun SQLiteStatement.row(keys: Int): Row =
    Row(DocumentId(getLong(0)), getText(1), List(keys) { sqlValue(2 + it) })

// Column types as SQLite numbers them: 1 integer, 2 float, 3 text, 4 blob, 5 null.
private fun SQLiteStatement.sqlValue(index: Int): SqlValue = when (getColumnType(index)) {
    1 -> SqlValue.Integer(getLong(index))
    2 -> SqlValue.Real(getDouble(index))
    5 -> SqlValue.Null
    else -> SqlValue.Text(getText(index))
}

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
