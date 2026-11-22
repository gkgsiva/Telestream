package com.telestream.app.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestream.app.TelestreamApp
import com.telestream.app.data.SavedItem
import com.telestream.app.data.UserList
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import com.telestream.app.ui.common.CenteredMessage
import com.telestream.app.ui.common.FocusCard
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.ui.common.formatDuration
import kotlinx.coroutines.launch

/** Dialog to put a media item into one of the user's lists, or a new one. */
@Composable
fun AddToListDialog(item: MediaItem, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val settings = TelestreamApp.instance.settings
    val lists by settings.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to a list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lists.forEach { list ->
                    val already = list.items.any { it.id == item.id }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                settings.addToList(list.id, SavedItem.of(item))
                                onAdded(list.name)
                            }
                        },
                        enabled = !already,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (already) "${list.name} (already added)" else list.name) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New list") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val id = settings.createList(newName)
                                settings.addToList(id, SavedItem.of(item))
                                onAdded(newName.ifBlank { "My list" })
                            }
                        },
                    ) { Icon(Icons.Default.Add, contentDescription = "Create list and add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Home tab that shows the user's lists. */
@Composable
fun ListsTab(onOpenList: (String) -> Unit) {
    val settings = TelestreamApp.instance.settings
    val lists by settings.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val isTv = LocalIsTv.current
    var showCreate by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your own lists of videos and music. Add items with the list button next to any video or song.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { showCreate = true }) { Text("New list") }
            }
        }
        if (lists.isEmpty()) {
            item { CenteredMessage("No lists yet.") }
        }
        items(lists, key = { it.id }) { list ->
            FocusCard(onClick = { onOpenList(list.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(list.name, style = MaterialTheme.typography.titleMedium)
                        Text(summary(list), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New list",
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                scope.launch { onOpenList(settings.createList(name)) }
            },
        )
    }
}

private fun summary(list: UserList): String {
    val videos = list.items.count { it.kind == MediaKind.VIDEO }
    val songs = list.items.size - videos
    return listOfNotNull(
        if (videos > 0) "$videos video${if (videos == 1) "" else "s"}" else null,
        if (songs > 0) "$songs song${if (songs == 1) "" else "s"}" else null,
    ).joinToString(", ").ifEmpty { "Empty" }
}

/** One list: play all, shuffle, play from an item, reorder, remove, rename, delete. */
@Composable
fun ListScreen(
    listId: String,
    onBack: () -> Unit,
    onPlay: (items: List<SavedItem>, index: Int, shuffle: Boolean) -> Unit,
) {
    val settings = TelestreamApp.instance.settings
    val lists by settings.lists.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val isTv = LocalIsTv.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val list = lists?.firstOrNull { it.id == listId }

    if (lists != null && list == null) {
        CenteredMessage("This list was deleted.", actionLabel = "Back", onAction = onBack)
        return
    }
    if (list == null) return

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(list.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPlay(list.items, 0, false) }, enabled = list.items.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play all")
                }
                OutlinedButton(onClick = { onPlay(list.items, 0, true) }, enabled = list.items.size > 1) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle")
                }
            }
        }
        if (list.items.isEmpty()) {
            item { CenteredMessage("This list is empty. Use the list button next to a video or song to add it.") }
        }
        itemsIndexed(list.items, key = { _, it -> it.id }) { index, item ->
            FocusCard(onClick = { onPlay(list.items, index, false) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.kind == MediaKind.VIDEO) Icons.Default.Movie else Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                item.subtitle.takeIf { it.isNotBlank() && it != item.title },
                                item.chatTitle.takeIf { it.isNotBlank() },
                                formatDuration(item.durationSec).takeIf { item.durationSec > 0 },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { scope.launch { settings.moveInList(list.id, item.id, -1) } }, enabled = index > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(
                        onClick = { scope.launch { settings.moveInList(list.id, item.id, 1) } },
                        enabled = index < list.items.size - 1,
                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                    IconButton(onClick = { scope.launch { settings.removeFromList(list.id, item.id) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove from list")
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog("Rename list", list.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { settings.renameList(list.id, name) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${list.name}\"?") },
            text = { Text("The videos and songs stay in Telegram; only this list is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        settings.deleteList(list.id)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
