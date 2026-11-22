package com.telestream.app.ui.home

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.telestream.app.ui.common.FocusCard
import com.telestream.app.ui.downloads.DownloadsTab
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.telestream.app.telegram.InvitePreview
import com.telestream.app.ui.common.TdImage
import com.telestream.app.ui.lists.ListsTab
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
    onOpenList: (String) -> Unit,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onAddToList: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
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
            val syncStatus by TelestreamApp.instance.sync.status.collectAsStateWithLifecycle()
            syncStatus?.let {
                TextButton(onClick = { TelestreamApp.instance.sync.requestSync() }) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onLogOut) { Text("Log out") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My chats") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Search") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("My lists") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Downloads") })
        }
        when (tab) {
            0 -> ChatsTab(onOpenChat, onPlay)
            1 -> SearchTab(onOpenChat, onPlay, onAddToList, onDownload)
            2 -> ListsTab(onOpenList)
            else -> DownloadsTab(onPlay)
        }
    }
}

@Composable
private fun ChatsTab(onOpenChat: (Long) -> Unit, onPlay: (List<MediaItem>, Int) -> Unit) {
    val app = TelestreamApp.instance
    val vm: ChatsViewModel = viewModel { ChatsViewModel(app.repository, app.settings) }
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val visible = if (vm.showHidden) vm.chats else vm.chats.filter { it.id !in hidden }
    val hiddenCount = vm.chats.count { it.id in hidden }
    when {
        vm.loading && vm.chats.isEmpty() -> Loading()
        vm.error != null && vm.chats.isEmpty() -> CenteredMessage(vm.error!!, actionLabel = "Retry", onAction = vm::refresh)
        vm.chats.isEmpty() -> CenteredMessage(
            "You have not joined any channels or groups yet. Use Search, then Find channels, to find and join some.",
            actionLabel = "Refresh",
            onAction = vm::refresh,
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ContinueWatchingRow(onPlay) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hiddenCount > 0) "${visible.size} shown, $hiddenCount hidden" else "${vm.chats.size} channels and groups",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (hiddenCount > 0) {
                        TextButton(onClick = { vm.showHidden = !vm.showHidden }) {
                            Text(if (vm.showHidden) "Hide hidden" else "Show hidden")
                        }
                    }
                    TextButton(onClick = { vm.managing = !vm.managing }) { Text(if (vm.managing) "Done" else "Hide chats") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            }
            items(visible, key = { it.id }) { chat ->
                val isHidden = chat.id in hidden
                val rowModifier = Modifier.alpha(if (isHidden) 0.5f else 1f)
                if (vm.managing || isHidden) {
                    ChatRow(chat, onClick = { onOpenChat(chat.id) }, modifier = rowModifier) {
                        IconButton(onClick = { vm.setHidden(chat.id, !isHidden) }) {
                            Icon(
                                if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (isHidden) "Show this chat" else "Hide this chat",
                            )
                        }
                    }
                } else {
                    ChatRow(chat, onClick = { onOpenChat(chat.id) }, modifier = rowModifier)
                }
            }
        }
    }
}

@Composable
private fun SearchTab(
    onOpenChat: (Long) -> Unit,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onAddToList: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
) {
    val app = TelestreamApp.instance
    val vm: SearchViewModel = viewModel { SearchViewModel(app.repository, app.settings) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val isTv = LocalIsTv.current
    val side = if (isTv) 48.dp else 12.dp
    val context = LocalContext.current
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie, song or channel")
    }
    val canVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) vm.search(text)
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
                label = {
                    Text(if (vm.scope == SearchScope.PUBLIC) "Channel name, @username or t.me link" else "Movie, song, file name…")
                },
                singleLine = true,
                trailingIcon = {
                    Row {
                        if (canVoice) {
                            IconButton(onClick = { runCatching { voice.launch(voiceIntent) } }) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice search")
                            }
                        }
                        IconButton(onClick = { keyboard?.hide(); vm.search() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isTv && canVoice) {
            item {
                OutlinedButton(onClick = { runCatching { voice.launch(voiceIntent) } }) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search by voice")
                }
            }
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
            if (!vm.searched) {
                item {
                    Text(
                        "Type a name or @username to find public channels and groups, or paste a private invite link (t.me/+...) to join a private group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.invite?.let { preview ->
                item {
                    InviteCard(preview, vm.joinMessage, onJoin = { vm.joinInvite(onOpenChat) }, onOpen = { onOpenChat(preview.chatId) })
                }
            }
            items(vm.channels, key = { it.id }) { chat -> ChatRow(chat, onClick = { onOpenChat(chat.id) }) }
            if (vm.searched && !vm.loading && vm.channels.isEmpty() && vm.invite == null && vm.error == null) {
                item { Text("No public channels or groups found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            itemsIndexed(vm.results, key = { _, item -> item.id }) { index, item ->
                MediaRow(
                    item,
                    onClick = { onPlay(vm.results.toList(), index) },
                    onAddToList = { onAddToList(item) },
                    onDownload = { onDownload(item) },
                )
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

@Composable
private fun InviteCard(preview: InvitePreview, message: String?, onJoin: () -> Unit, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TdImage(preview.photoFileId, preview.minithumbnail, Modifier.size(48.dp).clip(CircleShape))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(preview.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${preview.memberCount} members" + if (preview.needsApproval) " · admin approval needed" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.chatId != 0L) {
                    OutlinedButton(onClick = onOpen) { Text("Open") }
                } else {
                    Button(onClick = onJoin) { Text(if (preview.needsApproval) "Request to join" else "Join") }
                }
            }
            if (preview.description.isNotBlank()) {
                Text(preview.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
            }
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Videos the user started, newest first, with how far they got. */
@Composable
private fun ContinueWatchingRow(onPlay: (List<MediaItem>, Int) -> Unit) {
    val app = TelestreamApp.instance
    val entries by app.settings.continueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    if (entries.isEmpty()) return
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Continue watching", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch { app.settings.clearContinue() } }) { Text("Clear") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.item.id }) { entry ->
                var media by remember(entry.item.id) { mutableStateOf<MediaItem?>(null) }
                LaunchedEffect(entry.item.id) {
                    media = runCatching { app.repository.loadMessageMedia(entry.item.chatId, entry.item.messageId) }.getOrNull()
                }
                FocusCard(
                    onClick = { media?.let { onPlay(listOf(it), 0) } },
                    modifier = Modifier.width(220.dp),
                ) {
                    Column {
                        TdImage(media?.thumbFileId, media?.minithumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        if (entry.durationMs > 0) {
                            LinearProgressIndicator(
                                progress = { (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            entry.item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
