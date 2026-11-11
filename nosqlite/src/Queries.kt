package io.github.nailkhaf.nosqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

internal const val PENDING_CHANGES = "__pending_changes"

internal const val TRACKING_STATE = "__tracking_state"

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

    val tableExists: Statement<String, Boolean> = Statement(
        sql = "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?;",
        encode = { table -> bindText(1, table) },
        decode = { step() },
    )

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

    /** Inserts the input JSON, or applies it as a merge patch to the document with the same id. */
    fun upsert(collection: Collection): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?1)) " +
            "ON CONFLICT ${collection.idIndex.definition} DO UPDATE SET data = jsonb_patch(data, ?1);",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    /**
     * Rejects an update that changes `id`. Temp, like the tracking triggers, so it is created again whenever the
     * writer opens. `RAISE(ABORT)` undoes the statement and leaves the rest of the transaction in place.
     */
    fun idTrigger(collection: Collection): String = """
        CREATE TEMP TRIGGER IF NOT EXISTS nsq_${collection.name}_id
        BEFORE UPDATE ON main.${collection.table}
        WHEN OLD.data ->> '$.id' IS NOT NEW.data ->> '$.id'
        BEGIN
            SELECT RAISE(ABORT, 'id is immutable');
        END;
    """.trimIndent()

    fun insert(collection: Collection): Statement<String, Unit> = Statement(
        sql = "INSERT INTO ${collection.table} (data) VALUES (jsonb(?));",
        encode = { json -> bindText(1, json) },
        decode = { step() },
    )

    val createPendingChanges: Command<Unit> = command(
        """
        CREATE TABLE IF NOT EXISTS $PENDING_CHANGES (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            collection TEXT NOT NULL,
            document_id TEXT NOT NULL,
            action TEXT NOT NULL,
            change_set BLOB NOT NULL,
            timestamp INTEGER NOT NULL
        ) STRICT;
        """.trimIndent(),
    )

    val pendingChanges: Statement<Unit, List<PendingChange>> = Statement(
        sql = """
            SELECT id, collection, document_id, action, json(change_set), timestamp
            FROM $PENDING_CHANGES ORDER BY id;
        """.trimIndent(),
        encode = {},
        decode = rows {
            PendingChange(
                id = getLong(0),
                collection = getText(1),
                documentId = getText(2),
                action = pendingAction(getText(3)),
                changeSet = jsonObject(4),
                timestamp = Instant.fromEpochMilliseconds(getLong(5)),
            )
        },
    )

    /** Deletes the ids bound as one JSON array, such as `[1,2,3]`. */
    val deletePendingChanges: Statement<List<Long>, Unit> = Statement(
        sql = "DELETE FROM $PENDING_CHANGES WHERE id IN (SELECT value FROM json_each(?));",
        encode = { ids -> bindText(1, ids.joinToString(",", "[", "]")) },
        decode = { step() },
    )

    val createTrackingState: Command<Unit> = command(
        """
        CREATE TEMP TABLE IF NOT EXISTS $TRACKING_STATE (
            untracked INTEGER NOT NULL
        );
        """.trimIndent(),
    )

    val seedTrackingState: Command<Unit> = command("INSERT INTO $TRACKING_STATE (untracked) VALUES (0);")

    val setUntracked: Statement<Boolean, Unit> = Statement(
        sql = "UPDATE $TRACKING_STATE SET untracked = ?;",
        encode = { untracked -> bindLong(1, if (untracked) 1 else 0) },
        decode = { step() },
    )

    /**
     * Three temp triggers per tracked table that append the change unless the untracked flag is set. A trigger
     * stored in main cannot read a temp table, and INSERT/UPDATE/DELETE inside a trigger reject database-qualified
     * names, so the body stays unqualified. An update whose JSONB did not change records nothing. The update's
     * change set is an RFC 7396 merge patch built in SQL: nested objects are diffed, arrays are replaced whole, and
     * a removed key is null at the outermost removed path.
     */
    fun trackingTriggers(collection: Collection): List<String> {
        val table = "main.${collection.table}"
        val name = SqlValue.Text(collection.name).literal
        val guard = "WHEN NOT (SELECT untracked FROM $TRACKING_STATE)"
        val record = "INSERT INTO $PENDING_CHANGES (collection, document_id, action, change_set, timestamp)"
        val now = "CAST(unixepoch('subsec') * 1000 AS INTEGER)"
        return listOf(
            """
            CREATE TEMP TRIGGER IF NOT EXISTS nsq_${collection.name}_insert
            AFTER INSERT ON $table
            $guard
            BEGIN
                $record VALUES ($name, NEW.data ->> '$.id', 'insert', NEW.data, $now);
            END;
            """.trimIndent(),
            """
            CREATE TEMP TRIGGER IF NOT EXISTS nsq_${collection.name}_update
            AFTER UPDATE ON $table
            $guard AND OLD.data IS NOT NEW.data
            BEGIN
                $record
                VALUES (
                    $name,
                    NEW.data ->> '$.id',
                    'update',
                    (
                        WITH RECURSIVE
                        walk(path) AS (
                            SELECT '${'$'}'
                            UNION ALL
                            SELECT n.fullkey
                            FROM walk w
                            JOIN json_tree(NEW.data) n
                                ON n.path = w.path
                                AND n.key IS NOT NULL
                                AND n.type = 'object'
                                AND instr(n.fullkey, '[') = 0
                            JOIN json_tree(OLD.data) o
                                ON o.fullkey = n.fullkey AND o.type = 'object'
                        ),
                        emitted(fullkey, value) AS (
                            SELECT n.fullkey, jsonb_extract(NEW.data, n.fullkey)
                            FROM walk w
                            JOIN json_tree(NEW.data) n
                                ON n.path = w.path AND n.key IS NOT NULL AND instr(n.fullkey, '[') = 0
                            LEFT JOIN json_tree(OLD.data) o ON o.fullkey = n.fullkey
                            WHERE o.fullkey IS NULL
                                OR (
                                    NOT (o.type = 'object' AND n.type = 'object')
                                    AND jsonb_extract(OLD.data, n.fullkey) IS NOT jsonb_extract(NEW.data, n.fullkey)
                                )
                            UNION ALL
                            SELECT o.fullkey, jsonb('null')
                            FROM walk w
                            JOIN json_tree(OLD.data) o
                                ON o.path = w.path AND o.key IS NOT NULL AND instr(o.fullkey, '[') = 0
                            LEFT JOIN json_tree(NEW.data) n ON n.fullkey = o.fullkey
                            WHERE n.fullkey IS NULL
                        ),
                        numbered(fullkey, value, n) AS (
                            SELECT fullkey, value, row_number() OVER (ORDER BY fullkey) FROM emitted
                        ),
                        folded(n, patch) AS (
                            SELECT 0, jsonb('{}')
                            UNION ALL
                            SELECT folded.n + 1, jsonb_set(folded.patch, numbered.fullkey, numbered.value)
                            FROM folded
                            JOIN numbered ON numbered.n = folded.n + 1
                        )
                        SELECT patch FROM folded ORDER BY n DESC LIMIT 1
                    ),
                    $now
                );
            END;
            """.trimIndent(),
            """
            CREATE TEMP TRIGGER IF NOT EXISTS nsq_${collection.name}_delete
            AFTER DELETE ON $table
            $guard
            BEGIN
                $record VALUES ($name, OLD.data ->> '$.id', 'delete', jsonb('{}'), $now);
            END;
            """.trimIndent(),
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

private fun SQLiteStatement.jsonObject(index: Int): JsonObject =
    NoSqlite.Json.parseToJsonElement(getText(index)) as JsonObject

private fun pendingAction(text: String): PendingChange.Action = when (text) {
    "insert" -> PendingChange.Action.Insert
    "update" -> PendingChange.Action.Update
    "delete" -> PendingChange.Action.Delete
    else -> error("Unknown pending change action: $text")
}
