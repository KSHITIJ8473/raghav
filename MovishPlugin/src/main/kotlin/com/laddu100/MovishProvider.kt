package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.delay

class MovishProvider : MainAPI() {
    override var mainUrl = "https://movish.net"
    override var name = "Movish"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val headersMap = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private suspend fun <T> retry(times: Int = 3, delayMs: Long = 1000L, block: suspend () -> T): T {
        var exception: Exception? = null
        for (i in 1..times) {
            try {
                return block()
            } catch (e: Exception) {
                exception = e
                if (i < times) {
                    delay(delayMs)
                }
            }
        }
        throw exception ?: Exception("Retry failed")
    }

    // ==================== HOME PAGE CATEGORIES ====================

    override val mainPage = mainPageOf(
        // Core Feeds
        Pair("$mainUrl/trending", "Trending Now"),
        Pair("$mainUrl/movies", "Latest Movies"),
        Pair("$mainUrl/tv-shows", "Latest TV Shows"),
        Pair("$mainUrl/top-imdb", "Top IMDb"),

        // Premium Networks
        Pair("$mainUrl/network/netflix", "Netflix"),
        Pair("$mainUrl/network/disney", "Disney+"),
        Pair("$mainUrl/network/hbo", "HBO Max"),
        Pair("$mainUrl/network/amazon", "Prime Video"),
        Pair("$mainUrl/network/hulu", "Hulu"),
        Pair("$mainUrl/network/apple", "Apple TV+"),

        // Selected Popular Genres
        Pair("$mainUrl/genre/action", "Action"),
        Pair("$mainUrl/genre/adventure", "Adventure"),
        Pair("$mainUrl/genre/animation", "Animation"),
        Pair("$mainUrl/genre/comedy", "Comedy"),
        Pair("$mainUrl/genre/crime", "Crime"),
        Pair("$mainUrl/genre/documentary", "Documentary"),
        Pair("$mainUrl/genre/drama", "Drama"),
        Pair("$mainUrl/genre/fantasy", "Fantasy"),
        Pair("$mainUrl/genre/horror", "Horror"),
        Pair("$mainUrl/genre/mystery", "Mystery"),
        Pair("$mainUrl/genre/romance", "Romance"),
        Pair("$mainUrl/genre/science-fiction", "Sci-Fi"),
        Pair("$mainUrl/genre/thriller", "Thriller")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = retry { app.get(url, headers = headersMap, timeout = 60L).document }

        // Standard VOD poster cards
        val items = document.select("div.relative.group.overflow-hidden").mapNotNull { div ->
            val link = div.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            
            val img = link.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim().ifEmpty {
                div.selectFirst("h3")?.text()?.trim() ?: ""
            }
            if (title.isBlank()) return@mapNotNull null

            val poster = img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")

            val isTv = href.contains("/tv-show/")
            val type = if (isTv) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = fixUrl(poster)
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = retry { app.get(url, headers = headersMap, timeout = 60L).document }

        return document.select("div.relative.group.overflow-hidden").mapNotNull { div ->
            val link = div.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            
            val img = link.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim().ifEmpty {
                div.selectFirst("h3")?.text()?.trim() ?: ""
            }
            if (title.isBlank()) return@mapNotNull null

            val poster = img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")

            val isTv = href.contains("/tv-show/")
            val type = if (isTv) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    // ==================== LOAD DETAILS ====================

    override suspend fun load(url: String): LoadResponse {
        // --- Standard VOD handling ---
        val document = retry { app.get(url, headers = headersMap, timeout = 60L).document }
        val isSeries = url.contains("/tv-show/")

        // Retrieve local details
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h2")?.text()?.trim().orEmpty()
        
        val plotLocal = document.selectFirst("p.text-gray-300")?.text()?.trim().orEmpty()
        val posterLocal = document.selectFirst("div.movie-showcase img")?.attr("src")
            ?: document.selectFirst("img[alt=\"$title\"]")?.attr("src").orEmpty()

        // Extract TMDB ID from iframe player src
        var tmdbId: String? = null
        if (!isSeries) {
            val iframeSrc = document.selectFirst("iframe#video-iframe")?.attr("src")
            if (iframeSrc != null) {
                val match = Regex("""moviebox-embed/movie/(\d+)""").find(iframeSrc)
                tmdbId = match?.groupValues?.get(1)
            }
        } else {
            // Series doesn't embed player directly; fetch first episode page to find TMDB ID
            val firstEpLink = document.selectFirst("a[href*=\"/episode/\"]")?.attr("href")
            if (firstEpLink != null) {
                try {
                    val epDoc = retry { app.get(fixUrl(firstEpLink), headers = headersMap, timeout = 60L).document }
                    val epIframeSrc = epDoc.selectFirst("iframe#video-iframe")?.attr("src")
                    if (epIframeSrc != null) {
                        val match = Regex("""moviebox-embed/tv/(\d+)""").find(epIframeSrc)
                        tmdbId = match?.groupValues?.get(1)
                    }
                } catch (_: Exception) {}
            }
        }

        // Hydrate from Cinemeta using external_ids
        var enrichedPlot = plotLocal
        var enrichedPoster = posterLocal
        var enrichedBg: String? = null
        var enrichedRating: Double? = null
        var enrichedYear: Int? = null
        var enrichedActors: List<ActorData>? = null
        var cinemetaVideos: List<CinemetaVideo>? = null
        var imdbId: String? = null

        if (tmdbId != null) {
            imdbId = fetchImdbId(tmdbId, isSeries)
            if (imdbId != null) {
                try {
                    val metaType = if (isSeries) "series" else "movie"
                    val metaUrl = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"
                    val metaResponse = retry { app.get(metaUrl, headers = headersMap, timeout = 30L) }
                    if (metaResponse.code == 200) {
                        val metaJson = metaResponse.parsedSafe<CinemetaResponse>()
                        val meta = metaJson?.meta
                        if (meta != null) {
                            meta.description?.takeIf { it.isNotBlank() }?.let { enrichedPlot = it }
                            meta.poster?.takeIf { it.isNotBlank() }?.let { enrichedPoster = it }
                            meta.background?.takeIf { it.isNotBlank() }?.let { enrichedBg = it }
                            meta.imdbRating?.toDoubleOrNull()?.let { enrichedRating = it }
                            meta.year?.toIntOrNull()?.let { enrichedYear = it }
                            cinemetaVideos = meta.videos
                            val castList = meta.cast ?: emptyList()
                            if (castList.isNotEmpty()) {
                                enrichedActors = castList.map { ActorData(Actor(it)) }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (isSeries) {
            val slug = url.substringAfter("/tv-show/").substringBefore("?").trim('/')
            val episodes = mutableListOf<Episode>()
            if (tmdbId != null && cinemetaVideos != null && cinemetaVideos.isNotEmpty()) {
                // Map episodes dynamically from Cinemeta database
                for (v in cinemetaVideos) {
                    val s = v.season ?: continue
                    val e = v.episode ?: v.number ?: continue
                    // Skip specials (season 0) unless needed
                    if (s == 0) continue

                    val epDataUrl = "$mainUrl/episode/$slug/$s-$e"
                    episodes.add(
                        newEpisode(epDataUrl) {
                            this.name = v.name ?: "Episode $e"
                            this.season = s
                            this.episode = e
                            this.posterUrl = v.thumbnail
                            this.description = v.description ?: v.overview
                        }
                    )
                }
            } else {
                // Fallback: parse Season 1 episodes from page HTML
                document.select("a[href*=\"/episode/\"]").forEach { link ->
                    val href = fixUrl(link.attr("href"))
                    // Link formatted like /episode/teach-you-a-lesson/1-1
                    val match = Regex("""/(\d+)-(\d+)$""").find(href)
                    if (match != null) {
                        val s = match.groupValues[1].toIntOrNull() ?: 1
                        val e = match.groupValues[2].toIntOrNull() ?: return@forEach
                        val epDataUrl = "$mainUrl/episode/$slug/$s-$e"
                        episodes.add(
                            newEpisode(epDataUrl) {
                                this.name = "Episode $e"
                                this.season = s
                                this.episode = e
                            }
                        )
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = enrichedPoster
                this.backgroundPosterUrl = enrichedBg
                this.plot = enrichedPlot
                this.year = enrichedYear
                this.score = enrichedRating?.let { Score.from10(it.toFloat()) }
                this.actors = enrichedActors
                if (imdbId != null) addImdbId(imdbId)
                if (tmdbId != null) addTMDbId(tmdbId)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = enrichedPoster
                this.backgroundPosterUrl = enrichedBg
                this.plot = enrichedPlot
                this.year = enrichedYear
                this.score = enrichedRating?.let { Score.from10(it.toFloat()) }
                this.actors = enrichedActors
                if (imdbId != null) addImdbId(imdbId)
                if (tmdbId != null) addTMDbId(tmdbId)
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fetch the parent page (which is passed in data)
        val document = try {
            retry { app.get(data, headers = headersMap, timeout = 60L).document }
        } catch (e: Exception) {
            return false
        }
        val html = document.html()

        // Extract the allSources array: allSources: JSON.parse('...')
        val allSourcesMatch = Regex("""allSources:\s*JSON\.parse\((?:'|&#039;)(.*?)(?:'|&#039;)\)""").find(html)
        val sources = if (allSourcesMatch != null) {
            val rawJson = allSourcesMatch.groupValues[1]
            val cleanJson = rawJson
                .replace("\\u0022", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
            try {
                AppUtils.parseJson<List<MovishSource>>(cleanJson)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        var count = 0

        if (sources.isNotEmpty()) {
            for (source in sources) {
                val embedUrl = fixUrl(source.link)
                val labelLower = source.label?.lowercase()?.trim()
                if (labelLower == "flux" || labelLower == "storm") {
                    // Extract streams from the embedded custom player (moviebox-embed / vidrock-embed)
                    try {
                        val embedRes = retry { app.get(embedUrl, headers = headersMap, timeout = 60L) }
                        if (embedRes.code == 200) {
                            // Extract STREAMS array
                            val streamsMatch = Regex("""const STREAMS = (\[.*?\]);""").find(embedRes.text)
                            if (streamsMatch != null) {
                                val streamsJson = streamsMatch.groupValues[1]
                                val streams = AppUtils.parseJson<List<MovishStream>>(streamsJson)
                                for (stream in streams) {
                                    val q = when (stream.label?.lowercase()?.trim()) {
                                        "1080p" -> Qualities.P1080.value
                                        "720p" -> Qualities.P720.value
                                        "480p" -> Qualities.P480.value
                                        "360p" -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    callback(
                                        newExtractorLink(
                                            source = "Movish (${source.label})",
                                            name = "Movish (${stream.label ?: "Auto"})",
                                            url = stream.url,
                                            type = if (stream.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.headers = mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                            )
                                            this.referer = "https://movish.net/"
                                            this.quality = q
                                        }
                                    )
                                    count++
                                }
                            }

                            // Extract SUBS array
                            val subsMatch = Regex("""const SUBS = (\[.*?\]);""").find(embedRes.text)
                            if (subsMatch != null) {
                                val subsJson = subsMatch.groupValues[1]
                                try {
                                    val subs = AppUtils.parseJson<List<MovishSubtitle>>(subsJson)
                                    for (s in subs) {
                                        subtitleCallback(
                                            newSubtitleFile(
                                                lang = s.label ?: s.srclang ?: "English",
                                                url = s.url
                                            )
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    // Call standard extractor for external sources (Shadow -> vidlink.pro, Cine -> cinesrc.st)
                    try {
                        val loaded = loadExtractor(embedUrl, "https://movish.net/", subtitleCallback, callback)
                        if (loaded) {
                            count++
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Fallback: if we didn't resolve any links through allSources, try loading default iframe src
        if (count == 0) {
            val iframeSrc = document.selectFirst("iframe#video-iframe")?.attr("src")
            if (iframeSrc != null) {
                val embedUrl = fixUrl(iframeSrc)
                if (embedUrl.contains("moviebox-embed/")) {
                    try {
                        val embedRes = retry { app.get(embedUrl, headers = headersMap, timeout = 60L) }
                        if (embedRes.code == 200) {
                            val streamsMatch = Regex("""const STREAMS = (\[.*?\]);""").find(embedRes.text)
                            if (streamsMatch != null) {
                                val streamsJson = streamsMatch.groupValues[1]
                                val streams = AppUtils.parseJson<List<MovishStream>>(streamsJson)
                                for (stream in streams) {
                                    val q = when (stream.label?.lowercase()?.trim()) {
                                        "1080p" -> Qualities.P1080.value
                                        "720p" -> Qualities.P720.value
                                        "480p" -> Qualities.P480.value
                                        "360p" -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                    callback(
                                        newExtractorLink(
                                            source = "Movish",
                                            name = "Movish (${stream.label ?: "Auto"})",
                                            url = stream.url,
                                            type = if (stream.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.headers = mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                            )
                                            this.referer = "https://movish.net/"
                                            this.quality = q
                                        }
                                    )
                                    count++
                                }
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    try {
                        val loaded = loadExtractor(embedUrl, "https://movish.net/", subtitleCallback, callback)
                        if (loaded) {
                            count++
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return count > 0
    }

    // ==================== METADATA RESOLUTION HELPERS ====================

    private suspend fun fetchImdbId(tmdbId: String, isSeries: Boolean): String? {
        val type = if (isSeries) "tv" else "movie"
        val apiKey = "1865f43a0549ca50d341dd9ab8b29f49"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId/external_ids?api_key=$apiKey"
        return try {
            val res = retry { app.get(url, headers = headersMap, timeout = 30L) }
            if (res.code == 200) {
                res.parsedSafe<TmdbExternalIds>()?.imdbId
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class CinemetaResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    data class CinemetaMeta(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("moviedb_id") val moviedbId: Any? = null,
        @JsonProperty("videos") val videos: List<CinemetaVideo>? = null,
        @JsonProperty("cast") val cast: List<String>? = null
    )

    data class CinemetaVideo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("overview") val overview: String? = null
    )

    data class MovishStream(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class MovishSubtitle(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("srclang") val srclang: String? = null,
        @JsonProperty("default") val default: Boolean? = false
    )

    data class MovishSource(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String
    )
}