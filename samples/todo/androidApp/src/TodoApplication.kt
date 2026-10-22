package io.github.nailkhaf.todo

import android.app.Application
import io.github.nailkhaf.nosqlite.Location
import io.github.nailkhaf.todo.data.TaskRepository

class TodoApplication : Application() {
    val repository by lazy { TaskRepository(Location.File(filesDir.resolve("todo.db").path)) }
}
