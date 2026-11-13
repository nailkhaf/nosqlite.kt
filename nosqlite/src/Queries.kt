package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** The JSON key of the id every document carries. */
internal const val ID: String = "id"

/** The SQL expression of [ID]. The id index covers it, and an id lookup compares [Path.sql] for the same key. */
internal val ID_VALUE: String = Path(ID).sql

/** Lowercase canonical UUID text, the form [kotlin.uuid.Uuid.toString] returns, as a SQLite `GLOB`. */
internal val CANONICAL_UUID_GLOB: String =
    "[0-9a-f]".repeat(8) + "-" +
        "[0-9a-f]".repeat(4) + "-" +
        "[0-9a-f]".repeat(4) + "-" +
        "[0-9a-f]".repeat(4) + "-" +
        "[0-9a-f]".repeat(12)

internal const val PENDING_CHANGES: String = "${PREFIX}_pending_changes"

internal const val TRACKING_STATE: String = "${PREFIX}_tracking_state"

internal object Queries {

    /**
     * The collection table, then its id index `nsq_idx_<collection>_id`, then the trigger
     * `nsq_<collection>_id_immutable`, in that order. The caller runs them in its write transaction.
     */
    fun createTable(collection: String): List<Command<Unit>> =
        listOf(createTableCommand(collection), idIndex(collection), idTrigger(collection))

    private fun createTableCommand(collection: String): Command<Unit> = command(
        """
        CREATE TABLE IF NOT EXISTS ${table(collection)} (
            data BLOB NOT NULL,
            CONSTRAINT document_id CHECK (json_type(data, '$.$ID') IS 'text' AND ($ID_VALUE) GLOB '$CANONICAL_UUID_GLOB')
        ) STRICT;
        """.trimIndent(),
    )

    /** Every managed index name, by table. Indexes nosqlite does not name are left out. */
    val managedIndexes: Statement<Unit, Map<String, List<String>>> = Statement(
        sql = """
            SELECT tbl_name, name
            FROM sqlite_schema
            WHERE type = 'index' AND name GLOB '${INDEX_PREFIX}*'
        """.trimIndent(),
        encode = {},
        decode = {
            val grouped = mutableMapOf<String, MutableList<String>>()
            while (step()) grouped.getOrPut(getText(0)) { mutableListOf() } += getText(1)
            grouped
        },
    )

    fun idIndexName(collection: String): String = indexName(collection, "id")

    private fun idIndex(collection: String): Command<Unit> = command(
        "CREATE UNIQUE INDEX IF NOT EXISTS ${identifier(idIndexName(collection))} " +
            "ON ${table(collection)} ($ID_VALUE);",
    )

    fun createIndex(index: Index): Command<Unit> = command(
        "CREATE INDEX ${identifier(index.name)} ON ${index.collection.table} ${index.definition};",
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

    fun delete(collection: String, condition: Where): Statement<Unit, Int> =
        (Fragment("DELETE FROM ${table(collection)} WHERE ") + condition.render() + RETURNING).statement(countRows)

    /** Inserts the input JSON, or applies it as a merge patch to the document with the same id. */
    fun upsert(collection: String): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${table(collection)} (data) VALUES (jsonb(?1)) " +
            "ON CONFLICT ($ID_VALUE) DO UPDATE SET data = jsonb_patch(data, ?1);",
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

    fun insert(collection: Collection): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?));",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    val createPendingChanges: Command<Unit> = command(
        """
        CREATE TABLE IF NOT EXISTS ${identifier(PENDING_CHANGES)} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            collection TEXT NOT NULL,
            before BLOB,
            after BLOB,
            timestamp INTEGER NOT NULL
        ) STRICT;
        """.trimIndent(),
    )

    val pendingChanges: Statement<Unit, List<PendingChange>> = Statement(
        sql = """
            SELECT id, collection, json(before), json(after), timestamp
            FROM ${identifier(PENDING_CHANGES)} ORDER BY id;
        """.trimIndent(),
        encode = {},
        decode = rows {
            PendingChange(
                id = getLong(0),
                collection = getText(1),
                before = jsonObjectOrNull(2),
                after = jsonObjectOrNull(3),
                timestamp = Instant.fromEpochMilliseconds(getLong(4)),
            )
        },
    )

    /** Deletes the ids bound as one JSON array, such as `[1,2,3]`. */
    val deletePendingChanges: Statement<List<Long>, Unit> = Statement(
        sql = "DELETE FROM ${identifier(PENDING_CHANGES)} WHERE id IN (SELECT value FROM json_each(?));",
        encode = { ids -> bindText(1, ids.joinToString(",", "[", "]")) },
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
    fun trackingTriggers(collection: Collection): List<Command<Unit>> {
        val table = "main.${collection.table}"
        val name = SqlValue.Text(collection.name).literal
        val guard = "WHEN NOT (SELECT untracked FROM ${identifier(TRACKING_STATE)})"
        val record = "INSERT INTO ${identifier(PENDING_CHANGES)} (collection, before, after, timestamp) VALUES"
        val now = "CAST(unixepoch('subsec') * 1000 AS INTEGER)"
        return listOf(
            command("""
            CREATE TEMP TRIGGER IF NOT EXISTS ${identifier(triggerName(collection.name, "insert"))}
            AFTER INSERT ON $table
            $guard
            BEGIN
                $record ($name, NULL, NEW.data, $now);
            END;
            """.trimIndent()),
            command("""
            CREATE TEMP TRIGGER IF NOT EXISTS ${identifier(triggerName(collection.name, "update"))}
            AFTER UPDATE ON $table
            $guard AND OLD.data IS NOT NEW.data
            BEGIN
                $record ($name, OLD.data, NEW.data, $now);
            END;
            """.trimIndent()),
            command("""
            CREATE TEMP TRIGGER IF NOT EXISTS ${identifier(triggerName(collection.name, "delete"))}
            AFTER DELETE ON $table
            $guard
            BEGIN
                $record ($name, OLD.data, NULL, $now);
            END;
            """.trimIndent()),
        )
    }
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

private fun SQLiteStatement.jsonObjectOrNull(index: Int): JsonObject? {
    if (isNull(index)) return null
    return NoSqlite.Json.parseToJsonElement(getText(index)) as JsonObject
}

