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
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.future
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
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var effects: MusicEffects? = null
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

        // A fixed audio session so the equalizer and volume effects can attach to it.
        val audioSession = androidx.media3.common.util.Util.generateAudioSessionIdV21(this)
        player.audioSessionId = audioSession
        val fx = MusicEffects(audioSession)
        effects = fx
        scope.launch { app.settings.eqPreset.collect { fx.setPreset(it) } }
        scope.launch { app.settings.steadyVolume.collect { fx.setSteady(it) } }
        scope.launch {
            app.settings.fadeSeconds.collectLatest { seconds ->
                while (isActive) {
                    fx.fade(player, seconds)
                    delay(200)
                }
            }
        }
        // So the phone remote and casting can find the player without a controller.
        current = player

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback(app))
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        current = null
        effects?.release()
        effects = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Serves the player to the app and to Android Auto. Auto browses the user's lists and plays
     * the music in them; items it picks arrive with only a media id, which is resolved here.
     */
    private inner class Callback(private val app: TelestreamApp) : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Telestream"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val lists = app.settings.lists.first()
            val children: List<MediaItem> = if (parentId == ROOT) {
                lists.filter { list -> list.items.any { it.kind == MediaKind.AUDIO } }.map { folder(LIST_PREFIX + it.id, it.name) }
            } else if (parentId.startsWith(LIST_PREFIX)) {
                val listId = parentId.removePrefix(LIST_PREFIX)
                lists.firstOrNull { it.id == listId }?.items.orEmpty()
                    .filter { it.kind == MediaKind.AUDIO }
                    .map { item ->
                        MediaItem.Builder()
                            .setMediaId("$LIST_PREFIX$listId/${item.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(item.title)
                                    .setArtist(item.subtitle.ifBlank { item.chatTitle })
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build(),
                            )
                            .build()
                    }
            } else {
                emptyList()
            }
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolveAll(mediaItems).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            if (single != null && single.mediaId.startsWith(LIST_PREFIX) && single.requestMetadata.mediaUri == null) {
                // A song picked in Android Auto: queue the whole list from that song.
                val listId = single.mediaId.removePrefix(LIST_PREFIX).substringBefore('/')
                val itemId = single.mediaId.substringAfter('/')
                val saved = app.settings.lists.first().firstOrNull { it.id == listId }?.items.orEmpty()
                    .filter { it.kind == MediaKind.AUDIO }
                val loaded = saved.mapNotNull { s ->
                    runCatching { app.repository.loadMessageMedia(s.chatId, s.messageId) }.getOrNull()?.let { s.id to it.toMedia3() }
                }
                val start = loaded.indexOfFirst { it.first == itemId }.coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(loaded.map { it.second }, start, 0L)
            } else {
                val resolved = resolveAll(mediaItems)
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
            }
        }

        // Items from the app carry their Uri in requestMetadata.mediaUri, since the local Uri can
        // be lost on the way. Items with only an id ("chatId:messageId") are loaded from Telegram.
        private suspend fun resolveAll(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
            val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
            if (uri != null) return@mapNotNull withSubtitle(item.buildUpon().setUri(uri), item).build()
            val id = item.mediaId.substringAfterLast('/')
            val chatId = id.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val messageId = id.substringAfter(':').toLongOrNull() ?: return@mapNotNull null
            runCatching { app.repository.loadMessageMedia(chatId, messageId) }.getOrNull()?.toMedia3()
        }

        /** Subtitles downloaded in the app travel in requestMetadata extras, like the Uri. */
        private fun withSubtitle(builder: MediaItem.Builder, item: MediaItem): MediaItem.Builder {
            val extras = item.requestMetadata.extras ?: return builder
            val path = extras.getString(EXTRA_SUBTITLE_PATH) ?: return builder
            val subtitle = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(java.io.File(path)))
                .setMimeType(extras.getString(EXTRA_SUBTITLE_MIME) ?: androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(extras.getString(EXTRA_SUBTITLE_LANGUAGE))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            return builder.setSubtitleConfigurations(listOf(subtitle))
        }

        private fun folder(id: String, title: String) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
    }

    companion object {
        private const val ROOT = "root"
        private const val LIST_PREFIX = "list:"
        const val EXTRA_SUBTITLE_PATH = "subtitle_path"
        const val EXTRA_SUBTITLE_MIME = "subtitle_mime"
        const val EXTRA_SUBTITLE_LANGUAGE = "subtitle_language"

        /** The live player while the service runs. Main thread only. */
        @Volatile
        var current: Player? = null
            private set
    }

    private fun saveProgress(app: TelestreamApp, item: MediaItem?, positionMs: Long, durationMs: Long) {
        if (item == null) return
        val id = item.mediaId
        if (id.isEmpty()) return
        val saved = item.toSavedItem()
        scope.launch {
            if (saved != null && saved.kind == MediaKind.VIDEO) {
                app.settings.recordProgress(saved, positionMs, durationMs.coerceAtLeast(0L))
                if (durationMs > 0 && positionMs >= durationMs - durationMs / 20) {
                    val fileId = item.requestMetadata.mediaUri?.lastPathSegment?.toIntOrNull()
                    com.telestream.app.data.DownloadPolicy.onFinished(app, saved.id, fileId)
                }
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
