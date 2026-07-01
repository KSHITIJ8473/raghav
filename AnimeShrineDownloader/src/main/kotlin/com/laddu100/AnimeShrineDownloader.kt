package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class AnimeShrineDownloader : MainAPI() {
    override var mainUrl = "https://animeshrine.xyz"
    override var name = "AnimeShrineDownloader"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/json,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "a" to "Anime A-B",
        "c" to "Anime C-D",
        "e" to "Anime E-F",
        "g" to "Anime G-H",
        "n" to "Anime N-O",
        "s" to "Anime S-T"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = request.data
        val url = "$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url, headers = baseHeaders).parsedSafe<SearchApiResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
        val items = response.results.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url, headers = baseHeaders).parsedSafe<SearchApiResponse>() ?: return emptyList()
        return response.results.mapNotNull { it.toSearchResponse() }
    }

    // ============================================================
    //  Load — parse download page for metadata + episodes
    // ============================================================
    //
    //  KEY FIX: Instead of using regex to extract __next_f data (which fails
    //  on 400KB+ pushes for anime like One Piece), we search for patterns
    //  DIRECTLY in the raw HTML. The HTML contains escaped JSON like:
    //    \"id\":\"37854-1:1:1\",\"title\":\"...
    //  We search for these patterns directly, avoiding the regex performance
    //  issue on massive strings.
    //
    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/info/").substringBefore("?")
        if (id.isBlank()) return null
        val isMovie = url.contains("type=movie")

        val downloadUrl = if (isMovie) "$mainUrl/download/$id" else "$mainUrl/download/$id:1:1"
        val html = try {
            app.get(downloadUrl, headers = baseHeaders).text
        } catch (_: Exception) {
            return null
        }

        // Extract metadata from raw HTML (search near "animeData" keyword)
        val animeDataPos = html.indexOf("animeData")
        val animeDataSection = if (animeDataPos >= 0) {
            html.substring(animeDataPos, minOf(html.length, animeDataPos + 100000))
        } else ""

        val name = extractEscapedField(animeDataSection, "name")
            ?: Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { cleanTitle(it) }
            ?: return null

        val poster = extractEscapedField(animeDataSection, "poster")
            ?: Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: ""
        val background = extractEscapedField(animeDataSection, "background") ?: poster
        val plot = extractEscapedField(animeDataSection, "description")
            ?: Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val year = extractEscapedField(animeDataSection, "year")?.toIntOrNull()
        val rating = extractEscapedField(animeDataSection, "imdbRating")?.toFloatOrNull()
        val genres = extractEscapedList(animeDataSection, "genres")
        val cast = extractEscapedList(animeDataSection, "cast").map { ActorData(Actor(it)) }
        val languages = extractEscapedList(animeDataSection, "languages")
        val tags = if (languages.isNotEmpty()) genres + languages else genres

        if (isMovie) {
            return newMovieLoadResponse(name, url, TvType.AnimeMovie, id) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.actors = cast
            }
        }

        // Parse episodes DIRECTLY from raw HTML (no __next_f extraction needed)
        val episodes = parseEpisodesFromHtml(html, id)

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(name, url, TvType.AnimeMovie, id) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.actors = cast
            }
        }

        val eps = episodes.map { ep ->
            newEpisode(ep.id) {
                this.name = ep.title
                this.episode = ep.episode
                this.season = ep.season
                this.posterUrl = ep.thumbnail
                this.description = ep.overview
            }
        }

        return newTvSeriesLoadResponse(name, url, TvType.Anime, eps) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
            this.actors = cast
        }
    }

    // ============================================================
    //  Load Links — fetch fresh download page, extract stream URLs
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val downloadUrl = "$mainUrl/download/$data"
        val html = try {
            app.get(downloadUrl, headers = baseHeaders).text
        } catch (_: Exception) {
            return false
        }

        // Extract stream URLs DIRECTLY from raw HTML
        val streams = parseStreamsFromHtml(html)
        if (streams.isEmpty()) return false

        for (stream in streams) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = stream.name,
                    url = stream.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = when {
                        stream.name.contains("1080") -> Qualities.P1080.value
                        stream.name.contains("720") -> Qualities.P720.value
                        stream.name.contains("480") -> Qualities.P480.value
                        stream.name.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
        return true
    }

    // ============================================================
    //  Helpers: search for ESCAPED JSON fields directly in raw HTML
    // ============================================================
    //
    //  The HTML contains escaped JSON like: \"name\":\"One Piece\"
    //  We search for these patterns directly, avoiding the need to
    //  extract and unescape the entire __next_f data.
    //

    private fun extractEscapedField(section: String, field: String): String? {
        // Pattern: \"field\":\"value\"
        val pattern = Regex("""\\"$field\\":\\"((?:[^"\\]|\\.)*)\\"""")
        val match = pattern.find(section) ?: return null
        val value = match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
        return value.takeIf { it.isNotBlank() }
    }

    private fun extractEscapedList(section: String, field: String): List<String> {
        // Pattern: \"field\":[\"val1\",\"val2\",...]
        val pattern = Regex("""\\"$field\\":\[([^\]]*)\]""")
        val match = pattern.find(section) ?: return emptyList()
        return Regex("""\\"([^"]*)\\"""").findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
    }

    // ============================================================
    //  Parse episodes from raw HTML (escaped format)
    // ============================================================
    //  Pattern in HTML: \"id\":\"37854-1:1:1\",\"title\":\"Episode Title\"
    //  This works on ANY size page without regex performance issues.
    //
    private data class EpisodeInfo(
        val id: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val overview: String?,
        val thumbnail: String?
    )

    private fun parseEpisodesFromHtml(html: String, animeId: String): List<EpisodeInfo> {
        val episodes = mutableListOf<EpisodeInfo>()
        val seen = mutableSetOf<String>()
        val escapedId = Regex.escape(animeId)

        // Search for escaped episode IDs: \"id\":\"<id>:<season>:<episode>\",\"title\":\"...\"
        val pattern = Regex("""\\"id\\":\\"($escapedId:\d+:\d+(?:\.\d)?)\\",\\"title\\":\\"((?:[^"\\]|\\.)*)\\"""")
        for (match in pattern.findAll(html)) {
            val epId = match.groupValues[1]
            val dedupKey = epId.substringBefore(".")
            if (dedupKey in seen) continue
            seen.add(dedupKey)

            val parts = epId.split(":")
            val season = parts.getOrNull(1)?.substringBefore(".")?.toIntOrNull() ?: 1
            val episodePart = parts.getOrNull(2)?.substringBefore(".")?.toIntOrNull() ?: 1

            val title = match.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
                .ifBlank { "Episode $episodePart" }

            // Find overview and thumbnail near this match
            val contextStart = match.range.first
            val contextEnd = minOf(html.length, match.range.last + 500)
            val context = html.substring(contextStart, contextEnd)

            val overview = Regex("""\\"overview\\":\\"((?:[^"\\]|\\.)*)\\"""").find(context)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
            val thumbnail = Regex("""\\"thumbnail\\":\\"([^"]*)\\"""").find(context)?.groupValues?.get(1)

            episodes.add(EpisodeInfo(epId, title, season, episodePart, overview, thumbnail))
        }

        return episodes
    }

    // ============================================================
    //  Parse streams from raw HTML (escaped format)
    // ============================================================
    //  Pattern: \"name\":\"ANIMESHRINE 1080p\"...\"url\":\"https://dl.animeshrine.xyz/...\"
    //
    private data class StreamInfo(val name: String, val url: String)

    private fun parseStreamsFromHtml(html: String): List<StreamInfo> {
        val streams = mutableListOf<StreamInfo>()
        val seen = mutableSetOf<String>()

        val pattern = Regex("""\\"name\\":\\"(ANIMESHRINE[^"]*)\\"[^}]*?\\"url\\":\\"(https://dl\.animeshrine\.xyz/[^"]*)\\"""")
        for (match in pattern.findAll(html)) {
            val name = match.groupValues[1]
            val url = match.groupValues[2]
            if (url in seen) continue
            seen.add(url)
            streams.add(StreamInfo(name, url))
        }

        return streams
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.substringBefore(" | Anime Shrine")
        t = t.replace(Regex("^Download\\s+", RegexOption.IGNORE_CASE), "")
        t = t.substringBefore(" Dual Audio")
        t = t.substringBefore(" HD")
        t = t.substringBefore(" in Hindi")
        t = t.substringBefore(" Multi-Audio")
        return t.trim().trimEnd('(', '-', ':')
    }

    // ============================================================
    //  Data classes for search API
    // ============================================================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(
        @JsonProperty("query") val query: String?,
        @JsonProperty("total") val total: Int?,
        @JsonProperty("results") val results: List<SearchResult>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(
        @JsonProperty("id") val id: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("background") val background: String?,
        @JsonProperty("year") val year: String?,
        @JsonProperty("imdbRating") val imdbRating: String?,
        @JsonProperty("genres") val genres: List<String>?
    )

    private fun SearchResult.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val name = this.name ?: return null
        val poster = this.poster
        val year = this.year?.toIntOrNull()
        val rating = this.imdbRating?.toFloatOrNull()
        val isMovie = this.type == "movie"
        val url = "$mainUrl/info/$id?type=${if (isMovie) "movie" else "series"}"

        return if (isMovie) {
            newMovieSearchResponse(name, url, TvType.AnimeMovie) {
                this.posterUrl = poster
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(name, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }
}
