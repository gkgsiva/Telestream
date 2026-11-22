package com.telestream.app.telegram

/** Optional limits applied to search results on the device. */
data class SearchFilters(
    val size: SizeFilter = SizeFilter.ANY,
    val length: LengthFilter = LengthFilter.ANY,
    val quality: QualityFilter = QualityFilter.ANY,
    val date: DateFilter = DateFilter.ANY,
) {
    val isActive: Boolean get() = this != SearchFilters()

    fun matches(item: MediaItem, nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        size.matches(item.size) && length.matches(item.durationSec) && quality.matches(item) && date.matches(item.date, nowSec)
}

enum class SizeFilter(val label: String, private val min: Long, private val max: Long) {
    ANY("Any size", 0, Long.MAX_VALUE),
    SMALL("Under 500 MB", 0, 500L shl 20),
    MEDIUM("500 MB to 2 GB", 500L shl 20, 2L shl 30),
    LARGE("Over 2 GB", 2L shl 30, Long.MAX_VALUE);

    fun matches(bytes: Long) = this == ANY || bytes in min until max
}

enum class LengthFilter(val label: String, private val minSec: Int, private val maxSec: Int) {
    ANY("Any length", 0, Int.MAX_VALUE),
    SHORT("Under 10 min", 1, 600),
    MEDIUM("10 to 60 min", 600, 3600),
    LONG("Over 1 hour", 3600, Int.MAX_VALUE);

    /** Files without a known duration only match "Any length". */
    fun matches(sec: Int) = this == ANY || (sec > 0 && sec in minSec until maxSec)
}

enum class QualityFilter(val label: String, private val minHeight: Int, private val tokens: List<String>) {
    ANY("Any quality", 0, emptyList()),
    HD("720p", 700, listOf("720p")),
    FULL_HD("1080p", 1000, listOf("1080p", "fhd")),
    UHD("4K", 2000, listOf("2160p", "4k", "uhd"));

    /** Uses the video's height when Telegram knows it, otherwise the file name. */
    fun matches(item: MediaItem): Boolean {
        if (this == ANY) return true
        val shortSide = minOf(item.width, item.height).takeIf { it > 0 } ?: item.height
        if (shortSide > 0) {
            val next = entries.getOrNull(ordinal + 1)
            return shortSide >= minHeight && (next == null || shortSide < next.minHeight)
        }
        val text = (item.title + " " + item.subtitle).lowercase()
        return tokens.any { text.contains(it) }
    }
}

enum class DateFilter(val label: String, private val maxAgeSec: Long) {
    ANY("Any time", Long.MAX_VALUE),
    WEEK("This week", 7 * 86_400L),
    MONTH("This month", 31 * 86_400L),
    YEAR("This year", 366 * 86_400L);

    fun matches(dateSec: Int, nowSec: Long) = this == ANY || nowSec - dateSec <= maxAgeSec
}

/** Key that is the same for one file reposted in several chats. */
fun MediaItem.duplicateKey(): String =
    uniqueId.ifEmpty { "$size:$durationSec:${title.lowercase()}" }

/** Keeps the first copy of each file. */
fun List<MediaItem>.withoutDuplicates(): List<MediaItem> = distinctBy { it.duplicateKey() }

private val qualityWords = Regex(
    """(?i)\b(2160p|1080p|720p|576p|480p|360p|4k|uhd|fhd|hd|hdr10?|dv|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|""" +
        """web-?dl|web-?rip|webrip|blu-?ray|brrip|bdrip|hdrip|dvdrip|hdtv|aac\d?(\.\d)?|ac3|dd\+?5\.1|ddp?5\.1|atmos|esub|esubs|""" +
        """dual audio|multi|proper|repack|remux)\b""",
)

/** Title with quality, codec and file-type words removed, so the same movie in 720p and 1080p match. */
fun MediaItem.versionKey(): String {
    val base = title.lowercase()
        .replace(Regex("""\.(mkv|mp4|m4v|avi|mov|webm|ts)$"""), "")
        .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        .replace('.', ' ').replace('_', ' ')
        .replace(qualityWords, " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
    return "${kind.name}:$base"
}

/** "4K", "1080p", "720p" and so on, from the video size or the file name. */
fun MediaItem.qualityLabel(): String? {
    val shortSide = minOf(width, height).takeIf { it > 0 } ?: height
    if (shortSide > 0) {
        return when {
            shortSide >= 2000 -> "4K"
            shortSide >= 1000 -> "1080p"
            shortSide >= 700 -> "720p"
            shortSide >= 540 -> "576p"
            else -> "${shortSide}p"
        }
    }
    val text = (title + " " + subtitle).lowercase()
    return when {
        "2160p" in text || "4k" in text -> "4K"
        "1080p" in text -> "1080p"
        "720p" in text -> "720p"
        "480p" in text -> "480p"
        else -> null
    }
}

private fun MediaItem.qualityRank(): Int = when (qualityLabel()) {
    "4K" -> 4
    "1080p" -> 3
    "720p" -> 2
    null -> 0
    else -> 1
}

/**
 * Groups videos that are the same title in different qualities. Each group lists the best
 * quality first; groups keep the order in which they first appear.
 */
fun List<MediaItem>.groupVersions(): List<List<MediaItem>> {
    val groups = LinkedHashMap<String, MutableList<MediaItem>>()
    for (item in this) {
        val key = item.versionKey()
        // Very short names ("video", "1") would lump unrelated files together.
        val usable = item.kind == MediaKind.VIDEO && key.substringAfter(':').length >= 4
        groups.getOrPut(if (usable) key else "single:${item.id}") { mutableListOf() }.add(item)
    }
    return groups.values.map { group -> group.sortedWith(compareByDescending<MediaItem> { it.qualityRank() }.thenByDescending { it.size }) }
}
