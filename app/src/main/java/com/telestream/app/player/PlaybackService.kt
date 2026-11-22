package com.telestream.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.telestream.app.MainActivity
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the single ExoPlayer for video and music, so audio keeps playing in the background and
 * shows media controls in the notification and on the lock screen.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = application as TelestreamApp

        // Platform decoders first, FFmpeg (AC3, E-AC3, DTS, ...) as the fallback.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TelegramDataSource.Factory(app.td)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(ResumeTracker(player, app))

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(openApp)
            .build()

        // Save the playing item's position every few seconds so it can resume later.
        scope.launch {
            while (isActive) {
                delay(5_000)
                val p = session?.player ?: continue
                if (p.isPlaying) saveProgress(app, p.currentMediaItem, p.currentPosition, p.duration)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        // Media items arriving from a controller may lose their local Uri on the way, so we
        // carry it in requestMetadata.mediaUri and restore it here.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }

    private fun saveProgress(app: TelestreamApp, item: MediaItem?, positionMs: Long, durationMs: Long) {
        if (item == null) return
        val id = item.mediaId
        if (id.isEmpty()) return
        val saved = item.toSavedItem()
        scope.launch {
            if (saved != null && saved.kind == MediaKind.VIDEO) {
                app.settings.recordProgress(saved, positionMs, durationMs.coerceAtLeast(0L))
            } else {
                app.settings.saveResumePosition(id, if (durationMs in 1..positionMs) 0L else positionMs)
            }
        }
    }

    /** Saves progress on pause, and marks an item finished when it plays to the end. */
    private inner class ResumeTracker(private val player: Player, private val app: TelestreamApp) : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && player.playbackState != Player.STATE_ENDED) {
                saveProgress(app, player.currentMediaItem, player.currentPosition, player.duration)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val item = oldPosition.mediaItem
                saveProgress(app, item, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                saveProgress(app, player.currentMediaItem, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)
            }
        }
    }
}
