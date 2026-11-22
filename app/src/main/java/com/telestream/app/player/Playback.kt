package com.telestream.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.telestream.app.data.Settings
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import kotlinx.coroutines.guava.await
import androidx.media3.common.MediaItem as Media3Item

/** UI-side handle to [PlaybackService]. Create one per activity and [release] it in onDestroy. */
class Playback(context: Context, private val settings: Settings) {
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    suspend fun controller(): MediaController = future.await()

    /** Plays one video, resuming where the user stopped last time. */
    suspend fun playVideo(item: MediaItem) {
        val c = controller()
        val resumeAt = settings.resumePosition(item.id)
        c.setMediaItem(item.toMedia3(), resumeAt)
        c.prepare()
        c.play()
    }

    /** Plays a list of audio files as a queue, starting at [index]. */
    suspend fun playQueue(items: List<MediaItem>, index: Int) {
        val c = controller()
        c.setMediaItems(items.map { it.toMedia3() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun release() {
        MediaController.releaseFuture(future)
    }
}

fun MediaItem.toMedia3(): Media3Item {
    val uri = TelegramDataSource.uriFor(fileId)
    return Media3Item.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setRequestMetadata(Media3Item.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle.ifBlank { chatTitle })
                .setAlbumTitle(chatTitle)
                .setMediaType(
                    if (kind == MediaKind.VIDEO) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC
                )
                .build()
        )
        .build()
}
