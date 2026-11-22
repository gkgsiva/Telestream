package com.telestream.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestream.app.TelestreamApp
import com.telestream.app.data.SavedItem
import com.telestream.app.telegram.MediaItem
import com.telestream.app.ui.common.CenteredMessage
import com.telestream.app.ui.common.FocusCard
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.ui.common.TdImage
import com.telestream.app.ui.common.formatSize
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Videos and music saved on this device for offline playback. */
@Composable
fun DownloadsTab(onPlay: (List<MediaItem>, Int) -> Unit) {
    val app = TelestreamApp.instance
    val downloads by app.settings.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val isTv = LocalIsTv.current
    if (downloads.isEmpty()) {
        CenteredMessage("Nothing downloaded yet. Use the download button next to a video or song to keep it on this device.")
        return
    }
    // Items resolved so far, so a tap can queue every finished download.
    val resolved = remember { mutableMapOf<String, MediaItem>() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Downloads play without internet. They stay until you delete them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(downloads, key = { it.id }) { saved ->
            DownloadRow(saved, onResolved = { resolved[saved.id] = it }) { item ->
                val queue = downloads.mapNotNull { resolved[it.id] }.filter { it.kind == item.kind }
                onPlay(queue.ifEmpty { listOf(item) }, queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun DownloadRow(saved: SavedItem, onResolved: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    val app = TelestreamApp.instance
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<MediaItem?>(null) }
    var file by remember { mutableStateOf<TdApi.File?>(null) }
    var missing by remember { mutableStateOf(false) }

    LaunchedEffect(saved.id) {
        val loaded = runCatching { app.repository.loadMessageMedia(saved.chatId, saved.messageId) }.getOrNull()
        if (loaded == null) {
            missing = true
            return@LaunchedEffect
        }
        item = loaded
        onResolved(loaded)
        file = runCatching { app.repository.fileState(loaded.fileId) }.getOrNull()
        app.td.fileUpdates.filter { it.id == loaded.fileId }.collect { file = it }
    }

    val f = file
    val size = f?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L
    val done = f?.local?.isDownloadingCompleted == true
    val active = f?.local?.isDownloadingActive == true
    val progress = if (size > 0) (f?.local?.downloadedSize ?: 0L).toFloat() / size else 0f

    FocusCard(onClick = { item?.let(onPlay) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TdImage(item?.thumbFileId, item?.minithumbnail, Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(saved.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        missing -> "No longer available in Telegram"
                        done -> "Downloaded · ${formatSize(size)}"
                        active -> "Downloading ${(progress * 100).toInt()}% of ${formatSize(size)}"
                        f != null -> "Paused at ${(progress * 100).toInt()}%"
                        else -> "Checking…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!done && f != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            val current = item
            if (current != null && f != null && !done) {
                IconButton(onClick = {
                    scope.launch {
                        if (active) {
                            app.repository.pauseDownload(current.fileId, true)
                        } else {
                            runCatching { app.repository.pauseDownload(current.fileId, false) }
                                .onFailure { app.repository.startDownload(current) }
                        }
                    }
                }) {
                    Icon(
                        if (active) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (active) "Pause download" else "Resume download",
                    )
                }
            }
            IconButton(onClick = {
                scope.launch {
                    current?.let { app.repository.deleteDownload(it.fileId) }
                    app.settings.removeDownload(saved.id)
                }
            }) { Icon(Icons.Default.Delete, contentDescription = "Delete download") }
        }
    }
}
