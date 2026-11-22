package com.telestream.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.telestream.app.data.SavedItem
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

    /** The app-side items last queued, by media id, so screens can show their thumbnails. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()

    fun itemFor(mediaId: String?): MediaItem? = mediaId?.let { known[it] }

    private fun track(items: List<MediaItem>) {
        items.forEach { known[it.id] = it }
    }

    /** Plays one video, resuming where the user stopped last time. */
    suspend fun playVideo(item: MediaItem) {
        val c = controller()
        track(listOf(item))
        val resumeAt = settings.resumePosition(item.id)
        c.setMediaItem(item.toMedia3(), resumeAt)
        c.prepare()
        c.play()
    }

    /** Plays items as a queue, starting at [index]. A video resumes where it was left. */
    suspend fun playQueue(items: List<MediaItem>, index: Int, shuffle: Boolean = false) {
        val c = controller()
        track(items)
        val first = items.getOrNull(index)
        val startAt = if (first?.kind == MediaKind.VIDEO) settings.resumePosition(first.id) else 0L
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(items.map { it.toMedia3() }, index, startAt)
        c.prepare()
        c.play()
    }

    fun release() {
        MediaController.releaseFuture(future)
    }
}

/** Rebuilds the list entry for a playing item from its media id ("chatId:messageId") and metadata. */
fun Media3Item.toSavedItem(): SavedItem? {
    val chatId = mediaId.substringBefore(':').toLongOrNull() ?: return null
    val messageId = mediaId.substringAfter(':').toLongOrNull() ?: return null
    val meta = mediaMetadata
    val album = meta.albumTitle?.toString().orEmpty()
    val artist = meta.artist?.toString().orEmpty()
    return SavedItem(
        chatId = chatId,
        messageId = messageId,
        kind = if (meta.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO) MediaKind.VIDEO else MediaKind.AUDIO,
        title = meta.title?.toString().orEmpty(),
        subtitle = if (artist == album) "" else artist,
        chatTitle = album,
        durationSec = 0,
    )
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
