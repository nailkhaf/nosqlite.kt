package io.github.nailkhaf.nosqlite

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Inside `read { }` or `write { }`: documents can be read.
 * A read transaction sees one snapshot. It never nests: a read inside any transaction runs in that transaction.
 */
public open class ReadScope internal constructor(internal val connection: Connection, internal val pool: ConnectionPool)

/**
 * Inside `write { }`: documents can also be inserted, updated, patched and deleted.
 * A nested write is a savepoint inside its [parent]. The tables it changes are collected until it commits: a savepoint
 * hands them to its parent, the outermost transaction reports them to [ConnectionPool.onCommit] once its connection
 * is back in the pool. Rolling back drops them.
 */
public class WriteScope internal constructor(
    connection: Connection,
    pool: ConnectionPool,
    internal val parent: WriteScope?,
) : ReadScope(connection, pool) {
    internal var changes: PersistentSet<String> = persistentSetOf()
        private set

    internal fun changed(tables: Set<String>) {
        changes = changes.addingAll(tables)
    }
}

context(scope: ReadScope)
internal fun connection(): Connection = scope.connection

// Inside an active transaction of this pool, block runs in it, so a read inside a write sees its changes.
internal suspend fun <R> ConnectionPool.read(block: suspend context(ReadScope) () -> R): R {
    activeScope(this)?.let { active -> return context(active) { block() } }
    return withReader { connection ->
        val scope = ReadScope(connection, this)
        connection.transaction(Boundary.Read) { scope.activate(block) }
    }
}

internal suspend fun <R> ConnectionPool.write(block: suspend context(WriteScope) () -> R): R {
    activeScope(this)?.let { active ->
        check(active is WriteScope) { "Write inside a read transaction, start write { } outside of read { }" }
        val scope = WriteScope(active.connection, this, parent = active)
        return active.connection.transaction(Boundary.Savepoint) { scope.activate(block) }
            .also { active.changed(scope.changes) }
    }

    val (result, changes) = withWriter { connection ->
        val scope = WriteScope(connection, this, parent = null)
        connection.transaction(Boundary.Write) { scope.activate(block) } to scope.changes
    }
    if (changes.isNotEmpty()) onCommit(changes)
    return result
}

/** The SQL that opens, commits and rolls back one kind of transaction. */
private enum class Boundary(val begin: String, val commit: String, val rollback: List<String>, val nested: Boolean) {
    Read("BEGIN DEFERRED TRANSACTION;", "COMMIT;", listOf("ROLLBACK;"), nested = false),
    Write("BEGIN IMMEDIATE TRANSACTION;", "COMMIT;", listOf("ROLLBACK;"), nested = false),

    // Savepoints nest strictly, so one name is enough: `RELEASE` and `ROLLBACK TO` act on the most recent one.
    // `ROLLBACK TO` keeps the savepoint open, so it is released as well.
    Savepoint("SAVEPOINT sp;", "RELEASE sp;", listOf("ROLLBACK TO sp;", "RELEASE sp;"), nested = true),
}

/** Runs [block] between [boundary]'s begin and commit, rolling back when it throws or is cancelled. */
private suspend fun <R> Connection.transaction(boundary: Boundary, block: suspend () -> R): R {
    if (boundary.nested) {
        check(inTransaction()) { "Connection is not in a transaction, SAVEPOINT would start a new one" }
    } else {
        check(!inTransaction()) { "Connection is already in a transaction, BEGIN would fail" }
    }
    execSQL(boundary.begin)
    try {
        return block().also { execSQL(boundary.commit) }
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            try {
                boundary.rollback.forEach { execSQL(it) }
            } catch (suppressed: Throwable) {
                e.addSuppressed(suppressed)
            }
        }
        throw e
    }
}

/** The transaction the current coroutine runs in, when it belongs to [pool]. */
private suspend fun activeScope(pool: ConnectionPool): ReadScope? {
    val active = currentCoroutineContext()[ActiveScope]?.scope ?: return null
    require(active.pool === pool) { "Active transaction belongs to another database" }
    return active
}

private suspend fun <S : ReadScope, R> S.activate(block: suspend context(S) () -> R): R =
    withContext(ActiveScope(this)) { block() }

private class ActiveScope(val scope: ReadScope) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ActiveScope>
}
