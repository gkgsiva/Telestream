package com.telestream.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.ChatKind
import com.telestream.app.telegram.ChatSummary
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import java.io.File
import java.util.Locale

/** A clickable card that shows a clear outline and grows slightly when focused with a D-pad. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.scale(if (focused) 1.02f else 1f),
        shape = RoundedCornerShape(12.dp),
        color = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        interactionSource = interaction,
    ) {
        content()
    }
}

/** Shows a Telegram image: the tiny inline preview at once, then the real file once downloaded. */
@Composable
fun TdImage(
    fileId: Int?,
    minithumbnail: ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val preview = remember(minithumbnail) {
        minithumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    var path by remember(fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(fileId) {
        if (fileId != null) {
            path = runCatching { TelestreamApp.instance.repository.downloadSmallFile(fileId) }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val p = path
        if (p != null) {
            AsyncImage(model = File(p), contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else if (preview != null) {
            Image(bitmap = preview, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun MediaRow(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToList: (() -> Unit)? = null,
) {
    val isTv = LocalIsTv.current
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumbWidth = if (item.kind == MediaKind.VIDEO) (if (isTv) 176.dp else 128.dp) else (if (isTv) 96.dp else 72.dp)
            val thumbHeight = if (item.kind == MediaKind.VIDEO) thumbWidth * 9 / 16 else thumbWidth
            Box {
                TdImage(
                    fileId = item.thumbFileId,
                    minithumbnail = item.minithumbnail,
                    modifier = Modifier.width(thumbWidth).size(thumbWidth, thumbHeight).clip(RoundedCornerShape(8.dp)),
                )
                if (item.durationSec > 0) {
                    Text(
                        formatDuration(item.durationSec),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    item.title,
                    style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOf(
                    item.subtitle.takeIf { it.isNotBlank() && it != item.title },
                    item.chatTitle.takeIf { it.isNotBlank() },
                    formatSize(item.size).takeIf { item.size > 0 },
                    if (item.height > 0) "${item.height}p" else null,
                ).filterNotNull().joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onAddToList != null) {
                IconButton(onClick = onAddToList) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to a list")
                }
            }
        }
    }
}

@Composable
fun ChatRow(
    chat: ChatSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TdImage(chat.photoFileId, chat.minithumbnail, Modifier.size(48.dp).clip(CircleShape))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (chat.kind) {
                        ChatKind.CHANNEL -> "Channel"
                        ChatKind.GROUP -> "Group"
                        ChatKind.PRIVATE -> "Chat"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun SmallLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}
