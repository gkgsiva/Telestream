package com.telestream.app.ui.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.MediaFilter
import com.telestream.app.telegram.MediaItem
import com.telestream.app.ui.common.CenteredMessage
import com.telestream.app.ui.common.ChatRow
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.ui.common.Loading
import com.telestream.app.ui.common.MediaRow
import com.telestream.app.ui.common.SmallLoading

@Composable
fun HomeScreen(
    onOpenChat: (Long) -> Unit,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onLogOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val isTv = LocalIsTv.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Telestream", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLogOut) { Text("Log out") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My chats") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Search") })
        }
        when (tab) {
            0 -> ChatsTab(onOpenChat)
            else -> SearchTab(onOpenChat, onPlay)
        }
    }
}

@Composable
private fun ChatsTab(onOpenChat: (Long) -> Unit) {
    val app = TelestreamApp.instance
    val vm: ChatsViewModel = viewModel { ChatsViewModel(app.repository) }
    val isTv = LocalIsTv.current
    when {
        vm.loading && vm.chats.isEmpty() -> Loading()
        vm.error != null && vm.chats.isEmpty() -> CenteredMessage(vm.error!!, actionLabel = "Retry", onAction = vm::refresh)
        vm.chats.isEmpty() -> CenteredMessage(
            "You have not joined any channels or groups yet. Use Search to find public channels.",
            actionLabel = "Refresh",
            onAction = vm::refresh,
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${vm.chats.size} channels and groups",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            }
            items(vm.chats, key = { it.id }) { chat -> ChatRow(chat, onClick = { onOpenChat(chat.id) }) }
        }
    }
}

@Composable
private fun SearchTab(onOpenChat: (Long) -> Unit, onPlay: (List<MediaItem>, Int) -> Unit) {
    val app = TelestreamApp.instance
    val vm: SearchViewModel = viewModel { SearchViewModel(app.repository, app.settings) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val isTv = LocalIsTv.current
    val side = if (isTv) 48.dp else 12.dp

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = side, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                label = {
                    Text(if (vm.scope == SearchScope.PUBLIC) "Channel name, @username or t.me link" else "Movie, song, file name…")
                },
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
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchScope.entries) { s ->
                    FilterChip(selected = vm.scope == s, onClick = { vm.selectScope(s) }, label = { Text(s.label) })
                }
                if (vm.scope == SearchScope.MY_CHATS) {
                    item { Spacer(Modifier.width(16.dp)) }
                    items(MediaFilter.entries) { f ->
                        FilterChip(selected = vm.filter == f, onClick = { vm.selectFilter(f) }, label = { Text(f.label) })
                    }
                }
            }
        }

        if (!vm.searched && recent.isNotEmpty()) {
            item { Text("Recent searches", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { q -> AssistChip(onClick = { vm.search(q) }, label = { Text(q) }) }
                }
            }
        }

        vm.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }

        if (vm.scope == SearchScope.PUBLIC) {
            items(vm.channels, key = { it.id }) { chat -> ChatRow(chat, onClick = { onOpenChat(chat.id) }) }
            if (vm.searched && !vm.loading && vm.channels.isEmpty() && vm.error == null) {
                item { Text("No public channels or groups found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            itemsIndexed(vm.results, key = { _, item -> item.id }) { index, item ->
                MediaRow(item, onClick = { onPlay(vm.results.toList(), index) })
            }
            if (vm.searched && !vm.endReached) {
                item(key = "more-${vm.pagesLoaded}") {
                    LaunchedEffect(vm.pagesLoaded) { vm.loadMore() }
                    SmallLoading()
                }
            }
            if (vm.searched && vm.endReached && vm.results.isEmpty() && vm.error == null) {
                item {
                    Text(
                        "Nothing found in your chats. Try another word, or find a channel under \"Find channels\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (vm.loading && vm.scope == SearchScope.PUBLIC) item { SmallLoading() }
    }
}
