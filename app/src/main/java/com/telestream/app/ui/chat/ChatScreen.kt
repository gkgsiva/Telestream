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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
fun ChatScreen(chatId: Long, onBack: () -> Unit, onPlay: (List<MediaItem>, Int) -> Unit) {
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
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        TabRow(selectedTabIndex = vm.filter.ordinal) {
            MediaFilter.entries.forEach { f ->
                Tab(selected = vm.filter == f, onClick = { vm.selectFilter(f) }, text = { Text(f.label) })
            }
        }
        LazyColumn(
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
                MediaRow(item, onClick = { onPlay(vm.items.toList(), index) })
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
