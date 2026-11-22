package com.telestream.app.telegram

/** Season and episode numbers read from a title like "Show.Name.S01E02.1080p.mkv". */
data class EpisodeInfo(val show: String, val season: Int, val episode: Int)

/** A show found in a channel, with its episodes grouped by season. */
data class Show(val name: String, val seasons: Map<Int, List<Pair<Int, MediaItem>>>)

object SeriesParser {
    private val patterns = listOf(
        // Show.Name.S01E02, Show Name - s1e2, Show [S01 EP02]
        Regex("""^(.*?)[\s._\-\[(]*\bS(\d{1,2})[\s._\-]*E[Pp]?(\d{1,3})""", RegexOption.IGNORE_CASE),
        // Show Name Season 1 Episode 2
        Regex("""^(.*?)[\s._\-]*Season[\s._\-]*(\d{1,2})[\s._\-,]*(?:Episode|Ep)[\s._\-]*(\d{1,3})""", RegexOption.IGNORE_CASE),
        // Show Name 1x02
        Regex("""^(.*?)[\s._\-]+(\d{1,2})x(\d{2,3})\b""", RegexOption.IGNORE_CASE),
    )

    // Show Name EP 12 / Episode 12 / E12 (season 1)
    private val episodeOnly = Regex("""^(.*?)[\s._\-\[(]*\b(?:Episode|EP|E)[\s._\-]*(\d{1,4})\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): EpisodeInfo? {
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val show = clean(m.groupValues[1])
            return EpisodeInfo(show, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        val m = episodeOnly.find(text) ?: return null
        return EpisodeInfo(clean(m.groupValues[1]), 1, m.groupValues[2].toInt())
    }

    /** Groups videos into shows and seasons. Videos without episode numbers are left out. */
    fun group(items: List<MediaItem>): List<Show> {
        val parsed = items.mapNotNull { item ->
            val info = parse(item.title) ?: parse(item.subtitle) ?: return@mapNotNull null
            info to item
        }
        return parsed
            .groupBy { (info, _) -> key(info.show) }
            .map { (_, entries) ->
                val name = entries.first().first.show.ifBlank { "Untitled" }
                val seasons = entries
                    .groupBy { it.first.season }
                    .toSortedMap()
                    .mapValues { (_, eps) ->
                        eps.map { it.first.episode to it.second }
                            .distinctBy { it.first }
                            .sortedBy { it.first }
                    }
                Show(name, seasons)
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun clean(raw: String): String = raw
        .replace(Regex("""[._]+"""), " ")
        .replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
        .replace(Regex("""^[\s\-@#]+|[\s\-–:|]+$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun key(show: String) = show.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
}
