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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.AlertDialog
import com.telestream.app.player.Lyrics
import com.telestream.app.player.LyricsFinder
import com.telestream.app.player.SleepTimer
import com.telestream.app.cast.CastManager
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun PlayerScreen(
    player: Player?,
    onClose: () -> Unit,
    onOpenInVlc: (() -> Unit)? = null,
    current: AppMediaItem? = null,
    onPlayOnTv: (() -> Unit)? = null,
) {
    var pickingCast by remember { mutableStateOf(false) }
    var findingSubtitles by remember { mutableStateOf(false) }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Pip.canEnter = { player != null && player.isPlaying && player.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO }
        Pip.pause = { player?.pause() }
        onDispose {
            Pip.canEnter = null
            Pip.pause = null
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
                update = {
                    it.player = player
                    // No controls in the small floating window.
                    it.useController = !Pip.active
                    if (Pip.active) it.hideController()
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!Pip.active) {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Text("Close", color = Color.White)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (onPlayOnTv != null) {
                    TextButton(onClick = onPlayOnTv) { Text("Play on TV", color = Color.White) }
                }
                if (current != null) {
                    TextButton(onClick = { findingSubtitles = true }) { Text("Subtitles", color = Color.White) }
                }
                if (current != null && CastManager.available) {
                    TextButton(onClick = { pickingCast = true }) { Text("Cast", color = Color.White) }
                }
                if (onOpenInVlc != null) {
                    TextButton(onClick = onOpenInVlc) { Text("Open in VLC", color = Color.White) }
                }
            }
            if (CastManager.casting) CastingOverlay(Modifier.align(Alignment.Center))
            if (player != null) NextEpisodeCountdown(player, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
    if (findingSubtitles && current != null && player != null) {
        SubtitleSearchDialog(current, player, onDismiss = { findingSubtitles = false })
    }
    if (pickingCast && current != null) {
        CastPicker(
            onDismiss = { pickingCast = false },
            onPick = { route ->
                pickingCast = false
                CastManager.castTo(route, current, player?.currentPosition ?: 0L, player)
            },
        )
    }
    CastManager.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { CastManager.message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { CastManager.message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun CastingOverlay(modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Playing on ${CastManager.deviceName ?: "your TV"}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                IconButton(onClick = { CastManager.castPlayer?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Back 10 seconds")
                }
                IconButton(onClick = { CastManager.castPlayer?.pause() }) { Icon(Icons.Default.Pause, contentDescription = "Pause") }
                IconButton(onClick = { CastManager.castPlayer?.play() }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
                IconButton(onClick = { CastManager.castPlayer?.let { it.seekTo(it.currentPosition + 30_000) } }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Forward 30 seconds")
                }
            }
            TextButton(onClick = CastManager::stopCasting) { Text("Stop casting") }
        }
    }
}

@Composable
private fun CastPicker(onDismiss: () -> Unit, onPick: (androidx.mediarouter.media.MediaRouter.RouteInfo) -> Unit) {
    DisposableEffect(Unit) {
        CastManager.startDiscovery()
        onDispose { CastManager.stopDiscovery() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cast to") },
        text = {
            Column {
                if (CastManager.routes.isEmpty()) {
                    Text("Looking for Chromecast and Google TV devices on this Wi-Fi…")
                }
                CastManager.routes.forEach { route ->
                    TextButton(onClick = { onPick(route) }) { Text(route.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    var showLyrics by remember { mutableStateOf(false) }
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
        item { SleepAndLyricsRow(player, state, showLyrics) { showLyrics = !showLyrics } }
        if (showLyrics) {
            item { LyricsPanel(state.title, state.artist, duration, position) }
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

@Composable
private fun SleepAndLyricsRow(player: Player, state: NowPlaying, showLyrics: Boolean, onToggleLyrics: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var soundOpen by remember { mutableStateOf(false) }
    val endsAt = SleepTimer.endsAt
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { choosing = true }) {
            Icon(Icons.Default.Bedtime, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (endsAt == null) "Sleep timer" else "Sleep in " + formatMs((endsAt - now).coerceAtLeast(0)))
        }
        TextButton(onClick = onToggleLyrics) {
            Icon(Icons.Default.MusicNote, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (showLyrics) "Hide lyrics" else "Lyrics")
        }
        TextButton(onClick = { soundOpen = true }) {
            Icon(Icons.Default.GraphicEq, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Sound")
        }
    }
    if (soundOpen) SoundDialog(onDismiss = { soundOpen = false })
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Stop playing after") },
            text = {
                Column {
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        TextButton(onClick = { SleepTimer.start(player, minutes * 60_000L); choosing = false }) { Text("$minutes minutes") }
                    }
                    TextButton(onClick = { SleepTimer.endOfTrack(player); choosing = false }) { Text("End of this song") }
                    if (endsAt != null) {
                        TextButton(onClick = { SleepTimer.cancel(); choosing = false }) { Text("Turn off timer") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun LyricsPanel(title: String, artist: String, durationMs: Long, positionMs: Long) {
    var lyrics by remember(title, artist) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(title, artist) { mutableStateOf(true) }
    LaunchedEffect(title, artist) {
        loading = true
        lyrics = LyricsFinder.find(title, artist, durationMs)
        loading = false
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val found = lyrics
            when {
                loading -> Text("Looking for lyrics…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                found == null -> Text("No lyrics found for this song.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                found.synced.isNotEmpty() -> {
                    // Show the current line and a few around it, highlighted as the song plays.
                    val current = found.synced.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
                    val from = (current - 3).coerceAtLeast(0)
                    val to = (current + 6).coerceAtMost(found.synced.size)
                    for (i in from until to) {
                        val line = found.synced[i]
                        Text(
                            line.text.ifBlank { "♪" },
                            style = if (i == current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                else -> Text(found.plain, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Lyrics from LRCLIB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * In the last seconds of an episode with another one queued: offers to play the next one now, or
 * to stay. With autoplay off in Settings, it pauses at the end instead of moving on.
 */
@Composable
private fun NextEpisodeCountdown(player: Player, modifier: Modifier) {
    val app = com.telestream.app.TelestreamApp.instance
    val autoplay by app.settings.autoplayNext.collectAsStateWithLifecycle(initialValue = true)
    var remaining by remember { mutableStateOf(Long.MAX_VALUE) }
    var hasNext by remember { mutableStateOf(false) }
    var nextTitle by remember { mutableStateOf("") }
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    var currentId by remember { mutableStateOf("") }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            remaining = if (duration > 0) duration - player.currentPosition else Long.MAX_VALUE
            hasNext = player.hasNextMediaItem() && player.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
            currentId = player.currentMediaItem?.mediaId.orEmpty()
            nextTitle = if (hasNext) player.getMediaItemAt(player.nextMediaItemIndex).mediaMetadata.title?.toString().orEmpty() else ""
            if (hasNext && !autoplay && remaining < 1_500 && player.isPlaying) player.pause()
            delay(500)
        }
    }
    if (!hasNext || remaining > 15_000 || dismissedFor == currentId) return
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (autoplay) "Next episode in ${(remaining / 1000).coerceAtLeast(0)} s" else "Next episode",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(nextTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.Button(onClick = { player.seekToNextMediaItem(); player.play() }) { Text("Play now") }
                if (autoplay) {
                    TextButton(onClick = {
                        // Stay on this episode: drop the rest of the queue.
                        dismissedFor = currentId
                        val next = player.currentMediaItemIndex + 1
                        if (next < player.mediaItemCount) player.removeMediaItems(next, player.mediaItemCount)
                    }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun SubtitleSearchDialog(item: AppMediaItem, player: Player, onDismiss: () -> Unit) {
    val app = com.telestream.app.TelestreamApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiKey by app.settings.openSubtitlesKey.collectAsStateWithLifecycle(initialValue = "")
    val savedLanguages by app.settings.subtitleLanguages.collectAsStateWithLifecycle(initialValue = "en")
    var query by remember { mutableStateOf(com.telestream.app.subtitles.OpenSubtitles.queryFor(item)) }
    var languages by remember(savedLanguages) { mutableStateOf(savedLanguages) }
    var results by remember { mutableStateOf<List<com.telestream.app.subtitles.SubtitleResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        busy = true
        message = null
        scope.launch {
            try {
                app.settings.setSubtitleLanguages(languages)
                results = com.telestream.app.subtitles.OpenSubtitles.search(context, apiKey, query, languages, item)
                if (results.isEmpty()) message = "No subtitles found. Try a shorter name."
            } catch (e: Exception) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find subtitles") },
        text = {
            if (apiKey.isBlank()) {
                Text(
                    "Subtitles come from OpenSubtitles.com. Create a free account there, add an API consumer to get an API key, " +
                        "and enter it in Settings, then Subtitles.",
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        androidx.compose.material3.OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Movie or show name") },
                            singleLine = true,
                        )
                    }
                    item {
                        androidx.compose.material3.OutlinedTextField(
                            value = languages,
                            onValueChange = { languages = it },
                            label = { Text("Languages, like en,ta,hi") },
                            singleLine = true,
                        )
                    }
                    item { TextButton(enabled = !busy && query.isNotBlank(), onClick = { search() }) { Text(if (busy) "Working…" else "Search") } }
                    message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
                    itemsIndexed(results) { _, result ->
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val sub = com.telestream.app.subtitles.OpenSubtitles.download(context, apiKey, result)
                                    applySubtitle(player, sub)
                                    onDismiss()
                                } catch (e: Exception) {
                                    message = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(result.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${result.language.uppercase()} · ${result.downloads} downloads",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Swaps the playing item for one with the downloaded subtitle, keeping the position. */
private fun applySubtitle(player: Player, sub: com.telestream.app.subtitles.DownloadedSubtitle) {
    val currentItem = player.currentMediaItem ?: return
    val index = player.currentMediaItemIndex
    val position = player.currentPosition
    val extras = android.os.Bundle().apply {
        putString(com.telestream.app.player.PlaybackService.EXTRA_SUBTITLE_PATH, sub.file.absolutePath)
        putString(com.telestream.app.player.PlaybackService.EXTRA_SUBTITLE_MIME, sub.mimeType)
        putString(com.telestream.app.player.PlaybackService.EXTRA_SUBTITLE_LANGUAGE, sub.language)
    }
    val request = androidx.media3.common.MediaItem.RequestMetadata.Builder()
        .setMediaUri(currentItem.requestMetadata.mediaUri)
        .setExtras(extras)
        .build()
    player.replaceMediaItem(index, currentItem.buildUpon().setRequestMetadata(request).build())
    player.seekTo(index, position)
    player.play()
}

@Composable
private fun SoundDialog(onDismiss: () -> Unit) {
    val app = com.telestream.app.TelestreamApp.instance
    val scope = rememberCoroutineScope()
    val preset by app.settings.eqPreset.collectAsStateWithLifecycle(initialValue = -1)
    val steady by app.settings.steadyVolume.collectAsStateWithLifecycle(initialValue = false)
    val fade by app.settings.fadeSeconds.collectAsStateWithLifecycle(initialValue = 0)
    val presets = com.telestream.app.player.MusicEffects.presetNames
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sound") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Equalizer", style = MaterialTheme.typography.labelLarge)
                if (presets.isEmpty()) {
                    Text("This device has no equalizer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            androidx.compose.material3.FilterChip(
                                selected = preset < 0,
                                onClick = { scope.launch { app.settings.setEqPreset(-1) } },
                                label = { Text("Off") },
                            )
                        }
                        items(presets.size) { i ->
                            androidx.compose.material3.FilterChip(
                                selected = preset == i,
                                onClick = { scope.launch { app.settings.setEqPreset(i) } },
                                label = { Text(presets[i]) },
                            )
                        }
                    }
                }
                if (com.telestream.app.player.MusicEffects.steadyAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Steady volume")
                            Text("Evens out loud and quiet songs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = steady, onCheckedChange = { v -> scope.launch { app.settings.setSteadyVolume(v) } })
                    }
                }
                Text("Fade between songs", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 3, 6, 10).forEach { seconds ->
                        androidx.compose.material3.FilterChip(
                            selected = fade == seconds,
                            onClick = { scope.launch { app.settings.setFadeSeconds(seconds) } },
                            label = { Text(if (seconds == 0) "Off" else "$seconds s") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
