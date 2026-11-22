package com.telestream.app.telegram

import org.drinkless.tdlib.TdApi

enum class MediaKind { VIDEO, AUDIO }

/** Which kind of media to search for. FILES = documents whose type is video or audio (e.g. .mkv uploads). */
enum class MediaFilter(val label: String) {
    VIDEO("Videos"),
    MUSIC("Music"),
    FILES("Files"),
}

enum class ChatKind { CHANNEL, GROUP, PRIVATE }

data class ChatSummary(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
)

/** A playable video or audio file found in a Telegram message. */
data class MediaItem(
    val chatId: Long,
    val messageId: Long,
    val kind: MediaKind,
    val title: String,
    val subtitle: String,
    val chatTitle: String,
    val durationSec: Int,
    val size: Long,
    val mimeType: String,
    val fileId: Int,
    val thumbFileId: Int?,
    val minithumbnail: ByteArray?,
    val width: Int,
    val height: Int,
    val date: Int,
    /** Telegram's id for the file content; the same file reposted elsewhere keeps it. */
    val uniqueId: String = "",
) {
    /** Stable id used for resume positions and Media3 media ids. */
    val id: String get() = "$chatId:$messageId"

    companion object {
        private val videoExtensions = setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "3gp", "flv", "wmv")
        private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac")

        fun from(message: TdApi.Message, chatTitle: String): MediaItem? {
            return when (val content = message.content) {
                is TdApi.MessageVideo -> {
                    val v = content.video
                    item(
                        message, chatTitle, MediaKind.VIDEO,
                        title = firstLine(content.caption).ifBlank { v.fileName }.ifBlank { "Video" },
                        subtitle = v.fileName,
                        durationSec = v.duration, file = v.video, mime = v.mimeType,
                        thumb = v.thumbnail, mini = v.minithumbnail, width = v.width, height = v.height,
                    )
                }
                is TdApi.MessageAudio -> {
                    val a = content.audio
                    item(
                        message, chatTitle, MediaKind.AUDIO,
                        title = a.title.ifBlank { a.fileName }.ifBlank { "Audio" },
                        subtitle = a.performer,
                        durationSec = a.duration, file = a.audio, mime = a.mimeType,
                        thumb = a.albumCoverThumbnail, mini = a.albumCoverMinithumbnail, width = 0, height = 0,
                    )
                }
                is TdApi.MessageDocument -> {
                    val d = content.document
                    val kind = kindOf(d.mimeType, d.fileName) ?: return null
                    item(
                        message, chatTitle, kind,
                        title = d.fileName.ifBlank { firstLine(content.caption) }.ifBlank { "File" },
                        subtitle = firstLine(content.caption),
                        durationSec = 0, file = d.document, mime = d.mimeType,
                        thumb = d.thumbnail, mini = d.minithumbnail, width = 0, height = 0,
                    )
                }
                else -> null
            }
        }

        fun kindOf(mime: String?, fileName: String?): MediaKind? {
            val m = mime.orEmpty().lowercase()
            val ext = fileName.orEmpty().substringAfterLast('.', "").lowercase()
            return when {
                m.startsWith("video/") || ext in videoExtensions -> MediaKind.VIDEO
                m.startsWith("audio/") || ext in audioExtensions -> MediaKind.AUDIO
                else -> null
            }
        }

        private fun firstLine(text: TdApi.FormattedText?): String =
            text?.text.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        private fun item(
            message: TdApi.Message,
            chatTitle: String,
            kind: MediaKind,
            title: String,
            subtitle: String,
            durationSec: Int,
            file: TdApi.File,
            mime: String?,
            thumb: TdApi.Thumbnail?,
            mini: TdApi.Minithumbnail?,
            width: Int,
            height: Int,
        ) = MediaItem(
            chatId = message.chatId,
            messageId = message.id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            chatTitle = chatTitle,
            durationSec = durationSec,
            size = if (file.size > 0) file.size else file.expectedSize,
            mimeType = mime.orEmpty(),
            fileId = file.id,
            thumbFileId = thumb?.file?.id,
            minithumbnail = mini?.data,
            width = width,
            height = height,
            date = message.date,
            uniqueId = file.remote?.uniqueId.orEmpty(),
        )
    }
}

/** One page of results plus how to ask for the next one (null = no more). */
data class Page<C>(val items: List<MediaItem>, val next: C?)

data class InvitePreview(
    val link: String,
    val chatId: Long,
    val title: String,
    val memberCount: Int,
    val description: String,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
    val needsApproval: Boolean,
)

/** True for private invite links such as t.me/+AbC or t.me/joinchat/AbC. */
fun isInviteLink(text: String): Boolean {
    val t = text.trim()
    return Regex("""(?i)(https?://)?(t\.me|telegram\.me|telegram\.dog)/(\+|joinchat/)[\w-]+""").matches(t) ||
        t.startsWith("tg://join?invite=")
}
