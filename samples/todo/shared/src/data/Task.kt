package io.github.nailkhaf.todo.data

import io.github.nailkhaf.nosqlite.Collection
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Task(val id: Uuid, val title: String, val done: Boolean = false)

internal object Tasks : Collection("tasks") {
    override val id = primaryKey(Task::id)
    val byDone = index(Task::done)
}
