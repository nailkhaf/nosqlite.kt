package io.github.nailkhaf.todo.data

import io.github.nailkhaf.nosqlite.NoSqlite
import io.github.nailkhaf.nosqlite.Location
import io.github.nailkhaf.nosqlite.asc
import io.github.nailkhaf.nosqlite.delete
import io.github.nailkhaf.nosqlite.insert
import io.github.nailkhaf.nosqlite.invoke
import io.github.nailkhaf.nosqlite.observe
import io.github.nailkhaf.nosqlite.update
import io.github.nailkhaf.nosqlite.write
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** The tasks, stored in a nosqlite database at [location]. */
class TaskRepository(location: Location) {
    private val db = NoSqlite(location) { +Tasks }

    /** Open tasks first, each group in the order it was added. */
    val tasks: Flow<List<Task>> = with(db) { Tasks.observe<Task> { orderBy { Task::done.asc } } }

    suspend fun add(title: String) {
        db.write { Tasks.insert(Task(Uuid.random(), title)) }
    }

    suspend fun toggle(task: Task) {
        db.write { Tasks.byId(task.id.toString()).update(task.copy(done = !task.done)) }
    }

    suspend fun delete(task: Task) {
        db.write { Tasks.byId(task.id.toString()).delete() }
    }

    suspend fun clearDone() {
        db.write { Tasks.byDone(true).delete() }
    }
}
