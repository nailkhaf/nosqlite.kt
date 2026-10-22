package io.github.nailkhaf.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.nailkhaf.todo.data.Task
import io.github.nailkhaf.todo.data.TaskRepository
import kotlinx.coroutines.launch

@Composable
fun App(repository: TaskRepository) {
    val tasks by repository.tasks.collectAsState(emptyList())
    val scope = rememberCoroutineScope()

    TodoTheme {
        Scaffold(
            bottomBar = {
                if (tasks.any { it.done }) {
                    TextButton(
                        onClick = { scope.launch { repository.clearDone() } },
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text("Clear done") }
                }
            },
        ) { innerPadding ->
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp),
            ) {
                NewTaskField(onAdd = { title -> scope.launch { repository.add(title) } })
                LazyColumn(Modifier.weight(1f)) {
                    items(tasks, key = { it.id.toString() }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { scope.launch { repository.toggle(task) } },
                            onDelete = { scope.launch { repository.delete(task) } },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewTaskField(onAdd: (String) -> Unit) {
    var title by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = { Text("New task") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                onAdd(title.text.trim())
                title = TextFieldValue()
            },
            enabled = title.text.isNotBlank(),
        ) { Text("Add") }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Text(
            text = task.title,
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
