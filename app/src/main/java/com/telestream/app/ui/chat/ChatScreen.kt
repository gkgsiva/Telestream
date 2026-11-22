package com.telestream.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.MediaFilter
import com.telestream.app.telegram.MediaItem
import com.telestream.app.ui.common.CenteredMessage
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.ui.common.MediaRow
import com.telestream.app.ui.common.SmallLoading
import com.telestream.app.ui.common.TdImage

@Composable
fun ChatScreen(
    chatId: Long,
    onBack: () -> Unit,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onAddToList: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
) {
    val app = TelestreamApp.instance
    val vm: ChatViewModel = viewModel(key = "chat-$chatId") { ChatViewModel(app.repository, chatId) }
    val keyboard = LocalSoftwareKeyboardController.current
    val isTv = LocalIsTv.current
    val side = if (isTv) 48.dp else 12.dp

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = side - 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            val chat = vm.chat
            if (chat != null) {
                TdImage(chat.photoFileId, chat.minithumbnail, Modifier.size(36.dp).clip(CircleShape))
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                if (vm.isMember == false) {
                    Button(onClick = vm::join, enabled = !vm.joining, modifier = Modifier.padding(start = 8.dp)) { Text("Join") }
                }
            }
        }
        vm.joinMessage?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = side, vertical = 4.dp))
        }
        TabRow(selectedTabIndex = if (vm.seriesMode) MediaFilter.entries.size else vm.filter.ordinal) {
            MediaFilter.entries.forEach { f ->
                Tab(selected = !vm.seriesMode && vm.filter == f, onClick = { vm.selectFilter(f) }, text = { Text(f.label) })
            }
            Tab(selected = vm.seriesMode, onClick = { vm.showSeries() }, text = { Text("Series") })
        }
        if (vm.seriesMode) {
            SeriesList(vm, side, onPlay, onAddToList, onDownload)
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = side, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    label = { Text("Search in this chat") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { keyboard?.hide(); vm.search() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            vm.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }
            itemsIndexed(vm.items, key = { _, item -> item.id }) { index, item ->
                MediaRow(
                    item,
                    onClick = { onPlay(vm.items.toList(), index) },
                    onAddToList = { onAddToList(item) },
                    onDownload = { onDownload(item) },
                )
            }
            if (!vm.endReached) {
                item(key = "more-${vm.pagesLoaded}") {
                    LaunchedEffect(vm.pagesLoaded) { vm.loadMore() }
                    SmallLoading()
                }
            } else if (vm.items.isEmpty() && vm.error == null) {
                item {
                    CenteredMessage(
                        when (vm.filter) {
                            MediaFilter.VIDEO -> "No videos here."
                            MediaFilter.MUSIC -> "No music here."
                            MediaFilter.FILES -> "No video or audio files here."
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesList(
    vm: ChatViewModel,
    side: androidx.compose.ui.unit.Dp,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onAddToList: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
) {
    // Selected season per show name.
    val selected = remember { mutableStateMapOf<String, Int>() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = side, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (vm.seriesLoading) {
            item {
                Text(
                    "Scanning videos… ${vm.seriesScanned} so far",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!vm.seriesLoading && vm.shows.isEmpty()) {
            item { CenteredMessage("No episode names like S01E02 found in this chat.") }
        }
        vm.shows.forEach { show ->
            val seasons = show.seasons.keys.toList()
            val season = selected[show.name]?.takeIf { it in show.seasons } ?: seasons.first()
            val episodes = show.seasons[season].orEmpty()
            item(key = "show-${show.name}") {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(show.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${seasons.size} season${if (seasons.size == 1) "" else "s"} · ${show.seasons.values.sumOf { it.size }} episodes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (seasons.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            items(seasons) { n ->
                                FilterChip(
                                    selected = n == season,
                                    onClick = { selected[show.name] = n },
                                    label = { Text("Season $n") },
                                )
                            }
                        }
                    }
                }
            }
            itemsIndexed(episodes, key = { _, e -> "ep-${show.name}-${e.second.id}" }) { index, (number, item) ->
                Column {
                    Text(
                        "S${season}·E$number",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                    MediaRow(
                        item,
                        onClick = { onPlay(episodes.map { it.second }, index) },
                        onAddToList = { onAddToList(item) },
                        onDownload = { onDownload(item) },
                    )
                }
            }
        }
    }
}
