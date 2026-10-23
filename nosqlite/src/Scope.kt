package io.github.nailkhaf.nosqlite

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * Inside `read { }` or `write { }`: documents can be read.
 * A read transaction sees one snapshot. It never nests: a read inside any transaction runs in that transaction.
 * The scope belongs to the coroutine running the block: a coroutine started inside it must finish inside it, and
 * one that uses the scope after the block has returned fails.
 */
public open class ReadScope internal constructor(internal val connection: Connection, internal val pool: ConnectionPool) {
    @Volatile
    private var ended = false

    internal fun end() {
        ended = true
    }

    internal fun checkActive() {
        check(!ended) { "Transaction has ended, a coroutine started inside read { } or write { } must finish inside it" }
    }
}

/**
 * Inside `write { }`: documents can also be inserted, updated, patched and deleted.
 * A nested write is a savepoint that rolls back alone.
 */
public class WriteScope internal constructor(connection: Connection, pool: ConnectionPool) :
    ReadScope(connection, pool) {
    // The collections this transaction changed: a savepoint hands its own to the enclosing write when it releases.
    internal val changed: MutableSet<String> = mutableSetOf()
}

/** Records that the current write changed this collection. */
context(scope: WriteScope)
internal fun Collection<*>.changed() {
    scope.changed += name
}

context(scope: ReadScope)
internal fun connection(): Connection {
    scope.checkActive()
    return scope.connection
}

// Inside an active transaction of this pool, block runs in it, so a read inside a write sees its changes.
internal suspend fun <R> ConnectionPool.read(block: suspend context(ReadScope) () -> R): R {
    activeScope(this)?.let { active -> return context(active) { block() } }
    return withReader { connection ->
        val scope = ReadScope(connection, this)
        connection.transaction(Boundary.Read) { withScope(scope, block) }
    }
}

/** Runs [block] in a write; [onCommit] gets the collections it changed after the outermost write commits. */
internal suspend fun <R> ConnectionPool.write(
    onCommit: suspend (Set<String>) -> Unit = {},
    block: suspend context(WriteScope) () -> R,
): R {
    activeScope(this)?.let { active ->
        check(active is WriteScope) { "Write inside a read transaction, start write { } outside of read { }" }
        val scope = WriteScope(active.connection, this)
        return active.connection.transaction(Boundary.Savepoint) { withScope(scope, block) }
            .also { active.changed += scope.changed }
    }
    val (result, changed) = withWriter { connection ->
        val scope = WriteScope(connection, this)
        connection.transaction(Boundary.Write) { withScope(scope, block) } to scope.changed.toSet()
    }
    onCommit(changed)
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
    execute(boundary.begin)
    try {
        return block().also { execute(boundary.commit) }
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            try {
                boundary.rollback.forEach { execute(it) }
            } catch (suppressed: Throwable) {
                e.addSuppressed(suppressed)
            }
        }
        throw e
    }
}

private suspend fun Connection.execute(sql: String) {
    statement(sql) { statement -> exclusive { statement.step() } }
}

/** The transaction the current coroutine runs in, when it belongs to [pool]. */
private suspend fun activeScope(pool: ConnectionPool): ReadScope? {
    val active = currentCoroutineContext()[ActiveScope]?.scope ?: return null
    check(active.pool === pool) { "Active transaction belongs to another database" }
    return active
}

private suspend fun <S : ReadScope, R> withScope(scope: S, block: suspend context(S) () -> R): R = try {
    withContext(ActiveScope(scope)) { context(scope) { block() } }
} finally {
    scope.end()
}

private class ActiveScope(val scope: ReadScope) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ActiveScope>
}
