package com.telugu.prompter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptListScreen(
    scripts: List<Script>,
    remoteOn: Boolean,
    onRemote: () -> Unit,
    onOpen: (Script) -> Unit,
    onEdit: (Script) -> Unit,
    onNew: () -> Unit,
    onDelete: (Script) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Script?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scripts") },
                actions = {
                    IconButton(onClick = onRemote) {
                        Icon(
                            Icons.Default.SettingsRemote, "Laptop remote",
                            tint = if (remoteOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New") })
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(scripts, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(s) }) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
                        Column(Modifier.weight(1f).padding(top = 6.dp)) {
                            Text(s.title.ifBlank { "(Untitled)" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                s.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onOpen(s) }) { Icon(Icons.Default.PlayArrow, "Start") }
                        IconButton(onClick = { onEdit(s) }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this script?") },
            text = { Text(s.title) },
            confirmButton = { TextButton(onClick = { onDelete(s); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(script: Script, defaultLanguage: ScriptLanguage, onSave: (Script) -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf(script.title) }
    var body by remember { mutableStateOf(script.body) }
    // null = not chosen: the language of the letters is used (or the default for English letters).
    var chosen by remember { mutableStateOf(script.language) }
    val detected = ScriptLanguage.detect(body)
    val language = ScriptLanguage.fromCode(chosen) ?: detected ?: defaultLanguage
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
                actions = {
                    TextButton(
                        onClick = { onSave(script.copy(title = title.ifBlank { body.take(30) }, body = body, language = chosen)) },
                        enabled = body.isNotBlank(),
                    ) { Text("Save") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().imePadding()) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Spoken in" + if (chosen == null && detected != null) " (detected from the letters)" else "",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScriptLanguage.entries.forEach { l ->
                    FilterChip(selected = language == l, onClick = { chosen = l.code }, label = { Text(l.label) })
                }
            }
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text("Script (Telugu, Malayalam, Tamil or English)") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 32.sp),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
            )
        }
    }
}
