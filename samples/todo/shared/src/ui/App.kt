package io.github.nailkhaf.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.nailkhaf.todo.data.Task
import io.github.nailkhaf.todo.data.TaskRepository
import kotlinx.coroutines.launch

@Composable
fun App(repository: TaskRepository) {
    val tasks by repository.tasks.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeContentPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("New task") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val added = title.trim()
                            title = ""
                            scope.launch { repository.add(added) }
                        },
                        enabled = title.isNotBlank(),
                    ) { Text("Add") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(tasks, key = { it.id.toString() }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { scope.launch { repository.toggle(task) } },
                            onDelete = { scope.launch { repository.delete(task) } },
                        )
                    }
                }
                if (tasks.any { it.done }) {
                    TextButton(onClick = { scope.launch { repository.clearDone() } }) { Text("Clear done") }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Text(
            text = task.title,
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
