package com.telestream.app.ui.player

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
fun PlayerScreen(player: Player?, onClose: () -> Unit) {
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
    }
}

/** Bar at the bottom of the app while music plays: title, play/pause, open the player. */
@Composable
fun MiniPlayer(player: Player, state: NowPlaying, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
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
            if (state.isPlaying) {
                TextButton(onClick = { player.pause() }) { Text("Pause") }
            } else {
                IconButton(onClick = { player.play() }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
            }
        }
    }
}
