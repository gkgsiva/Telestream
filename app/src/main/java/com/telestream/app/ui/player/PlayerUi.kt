package com.telestream.app.ui.player

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.telestream.app.ui.common.FocusCard
import com.telestream.app.ui.common.TdImage
import com.telestream.app.telegram.MediaItem as AppMediaItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.ui.common.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** Compose-friendly snapshot of what the player is doing. */
class NowPlaying {
    var title by mutableStateOf("")
        internal set
    var artist by mutableStateOf("")
        internal set
    var isPlaying by mutableStateOf(false)
        internal set
    var hasMedia by mutableStateOf(false)
        internal set
    var isVideo by mutableStateOf(false)
        internal set
    var index by mutableStateOf(0)
        internal set
    var mediaId by mutableStateOf<String?>(null)
        internal set
    var speed by mutableStateOf(1f)
        internal set
    var queue by mutableStateOf<List<Pair<String, String>>>(emptyList())
        internal set
    var shuffle by mutableStateOf(false)
        internal set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        internal set
}

@Composable
fun rememberNowPlaying(player: Player?): NowPlaying {
    val state = remember { NowPlaying() }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun sync() {
            val meta = player.mediaMetadata
            state.title = meta.title?.toString().orEmpty()
            state.artist = meta.artist?.toString().orEmpty()
            state.isPlaying = player.isPlaying
            state.hasMedia = player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE
            state.isVideo = meta.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
            state.index = player.currentMediaItemIndex
            state.mediaId = player.currentMediaItem?.mediaId
            state.speed = player.playbackParameters.speed
            state.shuffle = player.shuffleModeEnabled
            state.repeatMode = player.repeatMode
            state.queue = (0 until player.mediaItemCount).map { i ->
                val m = player.getMediaItemAt(i).mediaMetadata
                m.title?.toString().orEmpty() to m.artist?.toString().orEmpty()
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        sync()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}

/** Full-screen player for video (and for music, showing the standard controls). */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(player: Player?, onClose: () -> Unit, onOpenInVlc: (() -> Unit)? = null) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Videos stop when the player is closed; music keeps playing in the background.
            if (player?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO) player?.pause()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowSubtitleButton(true)
                        setShowNextButton(true)
                        setShowPreviousButton(true)
                        controllerAutoShow = true
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        requestFocus()
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("Close", color = Color.White)
        }
        if (onOpenInVlc != null) {
            TextButton(onClick = onOpenInVlc, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("Open in VLC", color = Color.White)
            }
        }
    }
}

/** Bar at the bottom of the app while music plays: title, previous, play/pause, next. */
@Composable
fun MiniPlayer(player: Player, state: NowPlaying, art: AppMediaItem?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(art, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(state.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.artist.isNotBlank()) {
                    Text(
                        state.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { player.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
            IconButton(onClick = { if (state.isPlaying) player.pause() else player.play() }) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { player.seekToNext() }, enabled = state.index < state.queue.size - 1 || state.repeatMode != Player.REPEAT_MODE_OFF) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

/** Music screen: track info, progress, previous/play/next, shuffle, repeat, and the queue. */
@Composable
fun NowPlayingScreen(player: Player?, state: NowPlaying, art: AppMediaItem?, onClose: () -> Unit) {
    if (player == null) return
    val isTv = LocalIsTv.current
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (isTv) 64.dp else 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Now playing", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverArt(art, Modifier.size(if (isTv) 200.dp else 240.dp))
                Text(
                    state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (state.artist.isNotBlank()) {
                    Text(state.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Slider(
                    value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { if (duration > 0) player.seekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatMs(position), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatMs(duration), style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { player.shuffleModeEnabled = !state.shuffle }) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { player.seekToPrevious() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = { if (state.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(64.dp).focusRequester(playFocus),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = { player.seekToNext() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                    }
                    TextButton(onClick = {
                        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                        val next = speeds[(speeds.indexOfFirst { it == state.speed } + 1).mod(speeds.size)]
                        player.setPlaybackSpeed(next)
                    }) { Text(formatSpeed(state.speed)) }
                    IconButton(onClick = {
                        player.repeatMode = when (state.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = when (state.repeatMode) {
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                Player.REPEAT_MODE_ONE -> "Repeat one"
                                else -> "Repeat off"
                            },
                            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Queue · ${state.queue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(state.queue) { i, (title, artist) ->
            val current = i == state.index
            FocusCard(onClick = { player.seekTo(i, 0L); player.play() }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (current && state.isPlaying) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String = formatDuration((ms / 1000).toInt())

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/** Album cover or video thumbnail, with a music note when there is none. */
@Composable
private fun CoverArt(item: AppMediaItem?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (item != null && (item.thumbFileId != null || item.minithumbnail != null)) {
            TdImage(item.thumbFileId, item.minithumbnail, Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}
