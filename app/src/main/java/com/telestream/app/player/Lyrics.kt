package com.telestream.app.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val plain: String, val synced: List<LyricLine>)

/**
 * Looks up song lyrics on LRCLIB (lrclib.net), a free public lyrics database that needs no key.
 * Only the song title, artist and length are sent.
 */
object LyricsFinder {
    private val cache = HashMap<String, Lyrics?>()

    suspend fun find(title: String, artist: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val key = "$title|$artist"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val cleanTitle = clean(title)
        val result = runCatching {
            exact(cleanTitle, artist, durationMs) ?: search("$cleanTitle $artist".trim())
        }.getOrNull()
        synchronized(cache) { cache[key] = result }
        result
    }

    private fun exact(title: String, artist: String, durationMs: Long): Lyrics? {
        if (artist.isBlank()) return null
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
        val body = get(url) ?: return null
        return parse(JSONObject(body))
    }

    private fun search(query: String): Lyrics? {
        if (query.isBlank()) return null
        val body = get("https://lrclib.net/api/search?q=${enc(query)}") ?: return null
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            parse(arr.getJSONObject(i))?.let { return it }
        }
        return null
    }

    private fun parse(o: JSONObject): Lyrics? {
        val plain = o.optString("plainLyrics").takeIf { it != "null" }.orEmpty()
        val synced = parseSynced(o.optString("syncedLyrics").takeIf { it != "null" }.orEmpty())
        if (plain.isBlank() && synced.isEmpty()) return null
        return Lyrics(plain.ifBlank { synced.joinToString("\n") { it.text } }, synced)
    }

    private val timeTag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

    fun parseSynced(text: String): List<LyricLine> = text.lineSequence().flatMap { line ->
        val tags = timeTag.findAll(line).toList()
        val words = line.replace(timeTag, "").trim()
        tags.map { m ->
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val frac = m.groupValues[3]
            val ms = if (frac.isEmpty()) 0L else (frac + "00").take(3).toLong()
            LyricLine(min * 60_000 + sec * 1000 + ms, words)
        }.asSequence()
    }.sortedBy { it.timeMs }.toList()

    /** Drops file extensions, track numbers and bracketed tags such as (Official Video). */
    private fun clean(title: String): String = title
        .replace(Regex("""\.(mp3|m4a|flac|ogg|opus|wav|aac)$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), "")
        .replace(Regex("""^\d{1,3}\s*[-._)]\s*"""), "")
        .replace('_', ' ')
        .trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Telestream (Android)")
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
