package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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

    // ============================================================
    //  Homepage
    // ============================================================
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

    // ============================================================
    //  Search
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url, headers = baseHeaders).parsedSafe<SearchApiResponse>() ?: return emptyList()
        return response.results.mapNotNull { it.toSearchResponse() }
    }

    // ============================================================
    //  Load
    // ============================================================
    //
    //  URL format: <mainUrl>/info/<id>?type=<series|movie>&total=<totalEpisodes>
    //
    //  For SERIES:
    //    1. Try to fetch /download/<id>:1:1 and parse episodes from __next_f
    //    2. If that fails (large series like One Piece, 500KB+ page), generate
    //       episodes from the totalSub count from the search API
    //  For MOVIES:
    //    Fetch /download/<id> for metadata
    //
    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/info/").substringBefore("?")
        if (id.isBlank()) return null

        val isMovie = url.contains("type=movie")
        val totalEps = url.substringAfter("total=", "0").substringBefore("&").toIntOrNull() ?: 0

        // Fetch the download page for metadata + episode list
        val downloadUrl = if (isMovie) "$mainUrl/download/$id" else "$mainUrl/download/$id:1:1"

        val html = try {
            app.get(downloadUrl, headers = baseHeaders).text
        } catch (_: Exception) {
            // If fetch fails, generate episodes from totalSub
            return generateFallbackLoad(url, id, isMovie, totalEps)
        }

        val data = extractNextData(html)

        // Extract metadata
        val name = extractField(data, "name")
            ?: Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { cleanTitle(it) }
            ?: return generateFallbackLoad(url, id, isMovie, totalEps)

        val poster = extractField(data, "poster")
            ?: Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: ""
        val background = extractField(data, "background") ?: poster
        val plot = extractField(data, "description")
            ?: Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val year = extractField(data, "year")?.toIntOrNull()
        val rating = extractField(data, "imdbRating")?.toFloatOrNull()
        val genres = extractListField(data, "genres")
        val cast = extractListField(data, "cast").map { ActorData(Actor(it)) }
        val languages = extractListField(data, "languages")
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

        // Try to parse episodes from the page data
        val episodes = parseEpisodes(data, id)

        if (episodes.isEmpty() && totalEps > 0) {
            // Fallback for large series (One Piece, Pokemon, etc.)
            // Generate episodes from totalSub count
            val generatedEps = (1..totalEps).map { epNum ->
                EpisodeInfo("$id:1:$epNum", "Episode $epNum", 1, epNum, null, null)
            }
            return createSeriesLoadResponse(name, url, poster, background, plot, year, rating, tags, cast, generatedEps)
        }

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

        return createSeriesLoadResponse(name, url, poster, background, plot, year, rating, tags, cast, episodes)
    }

    private fun createSeriesLoadResponse(
        name: String, url: String, poster: String?, background: String?,
        plot: String?, year: Int?, rating: Float?, tags: List<String>,
        cast: List<ActorData>, episodes: List<EpisodeInfo>
    ): LoadResponse {
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

    private fun generateFallbackLoad(url: String, id: String, isMovie: Boolean, totalEps: Int): LoadResponse? {
        if (isMovie) {
            return newMovieLoadResponse("Anime", url, TvType.AnimeMovie, id) {}
        }
        if (totalEps <= 0) return null
        val eps = (1..totalEps).map { epNum ->
            EpisodeInfo("$id:1:$epNum", "Episode $epNum", 1, epNum, null, null)
        }
        return newTvSeriesLoadResponse("Anime", url, TvType.Anime, eps.map { ep ->
            newEpisode(ep.id) {
                this.name = ep.title
                this.episode = ep.episode
                this.season = ep.season
            }
        }) {}
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
    //  Load Links
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

        val streamData = extractNextData(html)
        val streams = parseStreams(streamData)
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
    //  Helper: Extract __next_f data from HTML
    // ============================================================
    private fun extractNextData(html: String): String {
        val pattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        return pattern.findAll(html).joinToString("") { match ->
            match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
        }
    }

    // ============================================================
    //  Helper: Extract a field from the data (searches near animeData)
    // ============================================================
    //  Instead of depth-counting to find the end of animeData (which is slow
    //  for 400KB+ objects), we search within a window after "animeData":{
    //  The metadata fields appear at the beginning, before the videos array.
    //
    private fun extractField(data: String, field: String): String? {
        val animeDataStart = data.indexOf("\"animeData\":{")
        if (animeDataStart < 0) return null

        // Search within the first 3000 chars of animeData (metadata is at the start)
        val searchEnd = minOf(data.length, animeDataStart + 3000)
        val searchArea = data.substring(animeDataStart, searchEnd)

        val fieldPattern = Regex(""""$field":"((?:[^"\\]|\\.)*)"""")
        val match = fieldPattern.find(searchArea) ?: return null
        val value = match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
        return value.takeIf { it.isNotBlank() }
    }

    // ============================================================
    //  Helper: Extract a list field from the data
    // ============================================================
    private fun extractListField(data: String, field: String): List<String> {
        val animeDataStart = data.indexOf("\"animeData\":{")
        if (animeDataStart < 0) return emptyList()

        val searchEnd = minOf(data.length, animeDataStart + 3000)
        val searchArea = data.substring(animeDataStart, searchEnd)

        val arrayPattern = Regex(""""$field":\[([^\]]*)\]""")
        val match = arrayPattern.find(searchArea) ?: return emptyList()
        return Regex(""""([^"]*)"""").findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
    }

    // ============================================================
    //  Helper: Parse episodes from __next_f data
    // ============================================================
    private data class EpisodeInfo(
        val id: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val overview: String?,
        val thumbnail: String?
    )

    private fun parseEpisodes(data: String, animeId: String): List<EpisodeInfo> {
        val episodes = mutableListOf<EpisodeInfo>()
        val seen = mutableSetOf<String>()

        val escapedId = Regex.escape(animeId)
        val pattern = Regex(""""id":"($escapedId:\d+:\d+(?:\.\d)?)","title":"((?:[^"\\]|\\.)*)"""")
        for (match in pattern.findAll(data)) {
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

            val contextStart = match.range.first
            val contextEnd = minOf(data.length, match.range.last + 500)
            val context = data.substring(contextStart, contextEnd)

            val overview = Regex(""""overview":"((?:[^"\\]|\\.)*)"""").find(context)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
            val thumbnail = Regex(""""thumbnail":"([^"]*)"""").find(context)?.groupValues?.get(1)

            episodes.add(EpisodeInfo(epId, title, season, episodePart, overview, thumbnail))
        }

        return episodes
    }

    // ============================================================
    //  Helper: Parse streams from __next_f data
    // ============================================================
    private data class StreamInfo(val name: String, val url: String)

    private fun parseStreams(data: String): List<StreamInfo> {
        val streams = mutableListOf<StreamInfo>()
        val seen = mutableSetOf<String>()

        val pattern = Regex(""""name":"(ANIMESHRINE[^"]*)"[^}]*?"url":"(https://dl\.animeshrine\.xyz/[^"]*)"""")
        for (match in pattern.findAll(data)) {
            val name = match.groupValues[1]
            val url = match.groupValues[2]
            if (url in seen) continue
            seen.add(url)
            streams.add(StreamInfo(name, url))
        }

        return streams
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
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("totalSub") val totalSub: Int?,
        @JsonProperty("totalDub") val totalDub: Int?
    )

    private fun SearchResult.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val name = this.name ?: return null
        val poster = this.poster
        val year = this.year?.toIntOrNull()
        val rating = this.imdbRating?.toFloatOrNull()
        val isMovie = this.type == "movie"
        // Store type AND totalSub in URL so load() can use them for fallback
        val total = this.totalSub ?: 0
        val url = "$mainUrl/info/$id?type=${if (isMovie) "movie" else "series"}&total=$total"

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
