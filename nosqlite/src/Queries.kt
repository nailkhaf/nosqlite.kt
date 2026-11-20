package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The JSON key of the id every document carries. */
internal const val ID: String = "id"

/** The SQL expression of [ID] in the document. */
internal val ID_VALUE: String = Path(ID).sql

/** The primary key column: the document's id as its 16 bytes, so rows and every index entry end with it. */
internal const val ID_KEY: String = "id"

/** The 16 bytes of the canonical UUID text [text], an SQL expression; `NULL` when it isn't hex. */
internal fun idBytes(text: String): String = "unhex(replace($text, '-', ''))"

/** Lowercase canonical UUID text, the form [kotlin.uuid.Uuid.toString] returns, as a SQLite `GLOB`. */
internal val CANONICAL_UUID_GLOB: String = listOf(8, 4, 4, 4, 12).joinToString("-") { "[0-9a-f]".repeat(it) }

internal const val PENDING_CHANGES: String = "${PREFIX}_pending_changes"

internal const val TRACKING_STATE: String = "${PREFIX}_tracking_state"

internal object Queries {

    /**
     * The collection table, then the trigger `nsq_<collection>_id_immutable`. The caller runs them in its write
     * transaction.
     */
    fun createTable(collection: String): List<Command<Unit>> =
        listOf(createTableCommand(collection), idTrigger(collection))

    /**
     * A `WITHOUT ROWID` table keyed by the id's 16 bytes: rows are stored in id order and SQLite ends every index
     * entry with the key, so a query without `orderBy`, and ties within an index, come in id order. The key is a
     * plain column, since SQLite rejects a generated one as a primary key; the check keeps it equal to the id in the
     * document.
     */
    private fun createTableCommand(collection: String): Command<Unit> = command(
        """
        CREATE TABLE IF NOT EXISTS ${table(collection)} (
            $ID_KEY BLOB NOT NULL PRIMARY KEY,
            data BLOB NOT NULL,
            CONSTRAINT document_id CHECK (
                json_type(data, '$.$ID') IS 'text' AND ($ID_VALUE) GLOB '$CANONICAL_UUID_GLOB'
                AND $ID_KEY = ${idBytes(ID_VALUE)}
            )
        ) STRICT, WITHOUT ROWID;
        """.trimIndent(),
    )

    /** Every managed index name, by table. Indexes nosqlite does not name are left out. */
    val managedIndexes: Statement<Unit, Map<String, List<String>>> = Statement(
        sql = """
            SELECT tbl_name, name
            FROM sqlite_schema
            WHERE type = 'index' AND name GLOB '$INDEX_PREFIX*'
        """.trimIndent(),
        encode = {},
        decode = {
            val grouped = mutableMapOf<String, MutableList<String>>()
            while (step()) grouped.getOrPut(getText(0)) { mutableListOf() } += getText(1)
            grouped
        },
    )

    fun createIndex(index: FieldIndex): Command<Unit> = command(
        "CREATE INDEX ${identifier(index.name)} ON ${table(index.collection.name)} ${index.definition};",
    )

    fun dropIndex(name: String): Command<Unit> = command("DROP INDEX ${identifier(name)};")

    fun <T> select(collection: String, serializer: KSerializer<T>, query: Query): Statement<Unit, List<T>> =
        selectSql(collection, query).statement(rows { decode(collection, serializer, this) })

    fun selectSql(collection: String, query: Query): Fragment {
        val order = sortKeys(collection, query.orderBy)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = " ORDER BY ") {
                "${it.path.orderSql} ${if (it.descending) "DESC" else "ASC"}"
            }
            .orEmpty()
        val page = Fragment(
            "$order LIMIT ? OFFSET ?;",
            listOf(SqlValue.Integer(query.limit.toLong()), SqlValue.Integer(query.offset.toLong())),
        )
        return Fragment("SELECT json(data) FROM ${table(collection)}") + where(query.condition) + page
    }

    fun count(collection: String, condition: Where?): Statement<Unit, Long> =
        (Fragment("SELECT count(*) FROM ${table(collection)}") + where(condition) + ";").statement {
            check(step()) { "count(*) returned no row" }
            getLong(0)
        }

    /** Applies the JSON as a merge patch to every match when [merge], or stores it as each whole document. */
    fun update(collection: String, json: String, merge: Boolean, condition: Where): Statement<Unit, Int> {
        val value = if (merge) "jsonb_patch(data, ?)" else "jsonb(?)"
        return (Fragment("UPDATE ${table(collection)} SET data = $value WHERE ", listOf(SqlValue.Text(json))) +
            condition.render() + RETURNING).statement(countRows)
    }

    fun delete(collection: String, condition: Where): Statement<Unit, Int> =
        (Fragment("DELETE FROM ${table(collection)} WHERE ") + condition.render() + RETURNING).statement(countRows)

    /**
     * Inserts the input JSON, or changes the document with the same id: applies it as a merge patch when [merge], and
     * stores it as the whole document otherwise. An update rather than `REPLACE`, which would delete the row and fire
     * the delete trigger.
     */
    fun upsert(collection: String, merge: Boolean): Statement<String, Unit> = Statement(
        // `WHERE true` tells SQLite the ON CONFLICT belongs to the INSERT, not to a join in the SELECT.
        sql = "${insertSql(collection)} WHERE true " +
            "ON CONFLICT ($ID_KEY) DO UPDATE SET data = ${if (merge) "jsonb_patch(data, ?1)" else "excluded.data"};",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    /**
     * Rejects an update that changes `id`. Named `nsq_<collection>_id_immutable` and stored in `main` with the table:
     * it reads only `OLD` and `NEW`, so it can be persistent. The tracking triggers stay temp, because a trigger in
     * `main` cannot read the temp tracking-state table. `RAISE(ABORT)` undoes the statement and leaves the rest of
     * the transaction in place.
     */
    private fun idTrigger(collection: String): Command<Unit> = command("""
        CREATE TRIGGER IF NOT EXISTS main.${identifier(triggerName(collection, "id_immutable"))}
        BEFORE UPDATE ON ${table(collection)}
        WHEN OLD.data ->> '$.$ID' IS NOT NEW.data ->> '$.$ID'
        BEGIN
            SELECT RAISE(ABORT, 'id is immutable');
        END;
    """.trimIndent())

    fun insert(collection: String): Statement<String, Unit> = Statement(
        sql = "${insertSql(collection)};",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    val createPendingChanges: Command<Unit> = command(
        """
        CREATE TABLE IF NOT EXISTS ${identifier(PENDING_CHANGES)} (
            id BLOB NOT NULL DEFAULT (randomblob(16)),
            collection TEXT NOT NULL,
            before BLOB,
            after BLOB,
            timestamp INTEGER NOT NULL
        ) STRICT;
        """.trimIndent(),
    )

    /** Binds the limit and the offset. */
    val pendingChanges: Statement<Pair<Int, Int>, List<PendingChange>> = Statement(
        sql = """
            SELECT id, collection, json(before), json(after), timestamp
            FROM ${identifier(PENDING_CHANGES)} ORDER BY rowid LIMIT ? OFFSET ?;
        """.trimIndent(),
        encode = { (limit, offset) ->
            bindLong(1, limit.toLong())
            bindLong(2, offset.toLong())
        },
        decode = rows {
            PendingChange(
                id = Uuid.fromByteArray(getBlob(0)),
                collection = getText(1),
                before = jsonObjectOrNull(2),
                after = jsonObjectOrNull(3),
                timestamp = Instant.fromEpochMilliseconds(getLong(4)),
            )
        },
    )

    /** Deletes the ids bound as one JSON array of their hex strings, such as `["…","…"]`. */
    val deletePendingChanges: Statement<List<Uuid>, Unit> = Statement(
        sql = "DELETE FROM ${identifier(PENDING_CHANGES)} WHERE id IN (SELECT unhex(value) FROM json_each(?));",
        encode = { ids -> bindText(1, ids.joinToString(",", "[", "]") { "\"${it.toHexString()}\"" }) },
        decode = { step() },
    )

    val createTrackingState: Command<Unit> = command(
        """
        CREATE TEMP TABLE IF NOT EXISTS ${identifier(TRACKING_STATE)} (
            untracked INTEGER NOT NULL
        );
        """.trimIndent(),
    )

    val seedTrackingState: Command<Unit> = command("INSERT INTO ${identifier(TRACKING_STATE)} (untracked) VALUES (0);")

    val setUntracked: Statement<Boolean, Unit> = Statement(
        sql = "UPDATE ${identifier(TRACKING_STATE)} SET untracked = ?;",
        encode = { untracked -> bindLong(1, if (untracked) 1 else 0) },
        decode = { step() },
    )

    /**
     * Temp triggers `nsq_<collection>_insert`, `_update` and `_delete` that append the change unless the untracked
     * flag is set. A trigger stored in main cannot read a temp table, and INSERT/UPDATE/DELETE inside a trigger
     * reject database-qualified names, so the body stays unqualified. An update whose JSONB did not change records
     * nothing.
     */
    fun trackingTriggers(collection: String): List<Command<Unit>> {
        val table = "main.${table(collection)}"
        val name = SqlValue.Text(collection).literal
        val guard = "WHEN NOT (SELECT untracked FROM ${identifier(TRACKING_STATE)})"
        val record = "INSERT INTO ${identifier(PENDING_CHANGES)} (collection, before, after, timestamp) VALUES"
        val now = "CAST(unixepoch('subsec') * 1000 AS INTEGER)"
        return listOf(
            command("""
            CREATE TEMP TRIGGER IF NOT EXISTS ${identifier(triggerName(collection, "insert"))}
            AFTER INSERT ON $table
            $guard
            BEGIN
                $record ($name, NULL, NEW.data, $now);
            END;
            """.trimIndent()),
            command("""
            CREATE TEMP TRIGGER IF NOT EXISTS ${identifier(triggerName(collection, "update"))}
            AFTER UPDATE ON $table
            $guard AND OLD.data IS NOT NEW.data
            BEGIN
                $record ($name, OLD.data, NEW.data, $now);
            END;
            """.trimIndent()),
            command("""
            CREATE TEMP TRIGGER IF NOT EXISTS ${identifier(triggerName(collection, "delete"))}
            AFTER DELETE ON $table
            $guard
            BEGIN
                $record ($name, OLD.data, NULL, $now);
            END;
            """.trimIndent()),
        )
    }
}

/** Inserts the JSON bound as `?1`, with the key taken from its id so the JSON is bound once. */
private fun insertSql(collection: String): String =
    "INSERT INTO ${table(collection)} ($ID_KEY, data) " +
        "SELECT ${idBytes("j ->> '$.$ID'")}, j FROM (SELECT jsonb(?1) AS j)"

/**
 * The keys [orderBy] sorts by, ending with the id in the direction of the key before it, so equal keys come in a
 * fixed order; keys after the id are dropped, since ids are unique. Without keys, none: the table and its indexes
 * already end in id order. The id costs nothing when an index has the keys, since SQLite ends every index entry with
 * it, nor when SQLite sorts in memory anyway.
 */
private fun sortKeys(collection: String, orderBy: List<Order>): List<Order> {
    for (order in orderBy) {
        val other = order.collection ?: continue
        require(other.name == collection) { "Collection '$collection' can't sort by an index of '${other.name}'" }
    }
    if (orderBy.isEmpty()) return orderBy
    val untilId = orderBy.indexOfFirst { it.path.field == ID }
    return if (untilId >= 0) {
        orderBy.subList(0, untilId + 1)
    } else {
        orderBy + Order(Path(ID), orderBy.last().descending)
    }
}

/** The SQL a sort key orders by: the primary key for `id`, which sorts like the canonical id text. */
private val Path.orderSql: String
    get() = if (field == ID) ID_KEY else sql

// Identifiers can't be bound, so they are quoted into the SQL text with their own quotes doubled.
internal fun identifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

private fun where(condition: Where?): Fragment =
    condition?.let { Fragment(" WHERE ") + it.render() } ?: Fragment.EMPTY

// A row per document a write touched, counted as they come: androidx.sqlite has no changes().
private const val RETURNING = " RETURNING 1;"

private val countRows: SQLiteStatement.() -> Int = {
    var count = 0
    while (step()) count++
    count
}

private fun SQLiteStatement.jsonObjectOrNull(index: Int): JsonObject? {
    if (isNull(index)) return null
    return NoSqlite.Json.parseToJsonElement(getText(index)) as JsonObject
}
