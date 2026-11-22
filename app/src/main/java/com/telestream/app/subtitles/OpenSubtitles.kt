package com.telestream.app.subtitles

import android.content.Context
import androidx.media3.common.MimeTypes
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.SeriesParser
import com.telestream.app.telegram.versionKey
import com.telestream.app.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SubtitleResult(val fileId: Int, val name: String, val language: String, val downloads: Int)

data class DownloadedSubtitle(val file: File, val language: String, val mimeType: String, val remaining: Int?)

/**
 * Finds and downloads subtitles from OpenSubtitles.com with the user's own API key
 * (free at opensubtitles.com, under API consumers). Only the title and languages are sent.
 */
object OpenSubtitles {
    private const val BASE = "https://api.opensubtitles.com/api/v1"

    /** A search phrase for [item]: the show name for episodes, or the cleaned-up title. */
    fun queryFor(item: MediaItem): String {
        val episode = SeriesParser.parse(item.title)
        return episode?.show ?: item.versionKey().substringAfter(':')
    }

    suspend fun search(context: Context, apiKey: String, query: String, languages: String, item: MediaItem?): List<SubtitleResult> =
        withContext(Dispatchers.IO) {
            val episode = item?.let { SeriesParser.parse(it.title) }
            // OpenSubtitles asks for parameters in alphabetical order, lower case.
            val params = sortedMapOf(
                "languages" to languages.lowercase().replace(" ", ""),
                "query" to query.lowercase().trim(),
            )
            if (episode != null) {
                params["season_number"] = episode.season.toString()
                params["episode_number"] = episode.episode.toString()
            }
            val url = "$BASE/subtitles?" + params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val body = JSONObject(call(context, url, apiKey, null))
            val data = body.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
                val file = attrs.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                SubtitleResult(
                    fileId = file.optInt("file_id"),
                    name = attrs.optString("release").ifBlank { file.optString("file_name") },
                    language = attrs.optString("language"),
                    downloads = attrs.optInt("download_count"),
                )
            }
        }

    suspend fun download(context: Context, apiKey: String, result: SubtitleResult): DownloadedSubtitle = withContext(Dispatchers.IO) {
        val answer = JSONObject(
            call(context, "$BASE/download", apiKey, JSONObject().put("file_id", result.fileId).put("sub_format", "srt").toString()),
        )
        val link = answer.optString("link").ifBlank { throw IllegalStateException(answer.optString("message", "No download link")) }
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val file = File(dir, "${result.fileId}.srt")
        val conn = URL(link).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        DownloadedSubtitle(file, result.language, MimeTypes.APPLICATION_SUBRIP, answer.optInt("remaining", -1).takeIf { it >= 0 })
    }

    private fun call(context: Context, url: String, apiKey: String, postBody: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Telestream v${UpdateChecker.installedVersion(context)}")
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 401 || code == 403 -> throw IllegalStateException("OpenSubtitles did not accept the API key. Check it in Settings.")
                code == 406 || code == 429 -> throw IllegalStateException(
                    runCatching { JSONObject(text).optString("message") }.getOrNull()?.ifBlank { null }
                        ?: "OpenSubtitles download limit reached for today.",
                )
                code !in 200..299 -> throw IllegalStateException("OpenSubtitles answered $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
