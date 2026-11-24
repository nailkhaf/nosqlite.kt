package io.github.nailkhaf.todo.data

import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.Location
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.insert
import io.github.nailkhaf.nosqlite.observe
import io.github.nailkhaf.nosqlite.update
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The tasks, stored in a nosqlite database at [location]. */
class TaskRepository(location: Location) {
    private val db = NoSqlite(location) { +Tasks }

    /** Open tasks first, newest first within each group. */
    val tasks: Flow<List<Task>> = with(db) { Tasks.byDone().observe<Task>() }

    // Uuid.generateV7() is still experimental.
    @OptIn(ExperimentalUuidApi::class)
    suspend fun add(title: String) {
        db.write { Tasks.insert(Task(Uuid.generateV7(), title)) }
    }

    suspend fun toggle(task: Task) {
        db.write { Tasks.byId(task.id).update(task.copy(done = !task.done)) }
    }

    suspend fun delete(task: Task) {
        db.write { Tasks.byId(task.id).delete() }
    }

    suspend fun clearDone() {
        db.write { Tasks.byDone(true).delete() }
    }
}
