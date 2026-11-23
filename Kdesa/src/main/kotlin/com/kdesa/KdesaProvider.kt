package com.kdesa

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.raghav.donation.DonationManager
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "Kdesa"

private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
private const val TMDB_BACK = "https://image.tmdb.org/t/p/original"

private const val UA =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"

private fun siteHeaders(referer: String): Map<String, String> {
    return mapOf(
        "Accept" to "*/*",
        "User-Agent" to UA,
        "Referer" to referer
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DiscoverItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("mediaType") val mediaType: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("description") val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DiscoverResp(val items: List<DiscoverItem>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SearchResp(val results: List<DiscoverItem>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DetailGenre(val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DetailSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DetailJson(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    val genres: List<DetailGenre>? = null,
    val status: String? = null,
    val seasons: List<DetailSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class EpisodeListSeason(
    val season: Int? = null,
    val episodes: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class EpisodeMeta(
    val episode: Int? = null,
    val name: String? = null,
    val runtime: Int? = null,
    val still: String? = null,
    val overview: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class EpisodesResp(
    val seasons: List<EpisodeListSeason>? = null,
    val episodes: List<EpisodeMeta>? = null
)

private suspend fun fetchEpisodesIndex(tmdbId: Int, season: Int): EpisodesResp? {
    return try {
        val response = app.get(
            "$EMBED/api/episodes/$tmdbId/$season?v=2",
            headers = embedHeaders(),
            timeout = 30L
        )
        parseJson<EpisodesResp>(response.text)
    } catch (e: Exception) {
        Log.d(TAG, "episode index: ${e.message}")
        null
    }
}

// the detail json is embedded in the rsc flight stream, a brace scan is the
// only way to cut it out without pulling in a full rsc parser
private fun extractDetailJson(rsc: String): String? {
    val key = "\"detail\":"
    val keyAt = rsc.indexOf(key)
    if (keyAt < 0) return null
    val start = rsc.indexOf('{', keyAt + key.length)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    var i = start
    while (i < rsc.length) {
        val c = rsc[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return rsc.substring(start, i + 1)
                }
            }
        }
        i++
    }
    return null
}

class KdesaProvider : MainAPI() {
    override var mainUrl = "https://7movies.ws"
    override var name = "KDesa"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "movie|" to "Latest Movies",
        "tv|" to "Latest TV Shows",
        "movie|28" to "Action Movies",
        "movie|878" to "Sci-Fi Movies",
        "movie|27" to "Horror Movies",
        "movie|35" to "Comedy Movies",
        "movie|16" to "Animated Movies",
        "tv|16" to "Animated Series",
        "tv|10765" to "Sci-Fi & Fantasy Series"
    )

    private suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("kdesa")?.takeIf { it.contains("7movies") }?.let {
            mainUrl = it.trimEnd('/')
        }
    }

    private fun DiscoverItem.toSearch(): SearchResponse? {
        val id = this.id ?: return null
        val type = when (this.mediaType) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> return null
        }
        val title = this.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val yearNum = year?.take(4)?.toIntOrNull()
        val scoreNum = rating?.let { Score.from10(it.toInt()) }
        return newMovieSearchResponse(title, "$mainUrl/$type|$id|$title", if (type == "movie") TvType.Movie else TvType.TvSeries) {
            this.posterUrl = poster?.takeIf { it.isNotBlank() }
            this.year = yearNum
            this.score = scoreNum
        }
    }

    // entries may arrive from sync with an older domain in front of the
    // payload, only the segment after the last slash matters
    private fun payloadParts(raw: String): List<String> {
        val parts = raw.split("|")
        if (parts.isEmpty()) return emptyList()
        return listOf(parts[0].substringAfterLast('/')) + parts.drop(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        refreshDomain()
        val (type, genre) = request.data.split("|", limit = 2)
        val genreParam = genre.takeIf { it.isNotBlank() }?.let { "&genre=$it" } ?: ""
        return try {
            val response = app.get(
                "$mainUrl/api/discover?page=$page&type=$type$genreParam",
                headers = siteHeaders("$mainUrl/"),
                timeout = 30L
            )
            val items = parseJson<DiscoverResp>(response.text).items.orEmpty()
            newHomePageResponse(request.name, items.mapNotNull { it.toSearch() }, items.isNotEmpty())
        } catch (e: Exception) {
            Log.d(TAG, "main page: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        refreshDomain()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val response = app.get(
                "$mainUrl/api/search?v=5&q=$encoded",
                headers = siteHeaders("$mainUrl/"),
                timeout = 30L
            )
            parseJson<SearchResp>(response.text).results.orEmpty().mapNotNull { it.toSearch() }
        } catch (e: Exception) {
            Log.d(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchDetail(type: String, id: Int): DetailJson? {
        return try {
            val response = app.get(
                "$mainUrl/$type/$id?_rsc=kdesa",
                headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to UA,
                    "RSC" to "1",
                    "Referer" to "$mainUrl/"
                ),
                timeout = 30L
            )
            extractDetailJson(response.text)?.let { parseJson<DetailJson>(it) }
        } catch (e: Exception) {
            Log.d(TAG, "detail: ${e.message}")
            null
        }
    }

    private fun numberedEpisodes(tmdbId: Int, title: String, seasons: List<DetailSeason>): List<Episode> {
        return seasons.filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }.flatMap { season ->
            (1..(season.episodeCount ?: 0)).map { ep ->
                newEpisode("tv|$tmdbId|${season.seasonNumber}|$ep|$title") {
                    this.season = season.seasonNumber
                    this.episode = ep
                }
            }
        }
    }

    private suspend fun episodesForShow(tmdbId: Int, title: String, detail: DetailJson): List<Episode> {
        val seasonMap = fetchEpisodesIndex(tmdbId, 1)?.seasons.orEmpty()
            .mapNotNull { s -> s.season?.let { it to (s.episodes ?: 0) } }
            .toMap()

        val seasons = seasonMap.filterKeys { it > 0 && (seasonMap[it] ?: 0) > 0 }
            .ifEmpty {
                detail.seasons.orEmpty().filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }
                    .mapNotNull { s -> s.seasonNumber?.let { it to (s.episodeCount ?: 0) } }
                    .toMap()
            }
        if (seasons.isEmpty()) return numberedEpisodes(tmdbId, title, detail.seasons.orEmpty())

        // long running shows carry dozens of seasons, fetching them one by
        // one kept the episode list loading for half a minute
        val grouped = coroutineScope {
            val gate = Semaphore(6)
            seasons.toSortedMap().map { (season, count) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val meta = fetchEpisodesIndex(tmdbId, season)?.episodes
                        if (meta.isNullOrEmpty()) {
                            (1..count).map { ep ->
                                season to newEpisode("tv|$tmdbId|$season|$ep|$title") {
                                    this.season = season
                                    this.episode = ep
                                }
                            }
                        } else {
                            meta.mapNotNull { ep ->
                                val number = ep.episode ?: return@mapNotNull null
                                val built = newEpisode("tv|$tmdbId|$season|$number|$title") {
                                    this.name = ep.name?.takeIf { it.isNotBlank() }
                                    this.season = season
                                    this.episode = number
                                    this.posterUrl = ep.still?.takeIf { it.isNotBlank() }
                                    this.description = ep.overview?.takeIf { it.isNotBlank() }
                                    this.runTime = ep.runtime
                                }
                                season to built
                            }
                        }
                    }
                }
            }.flatMap { it.await() }
        }
        return grouped.sortedBy { (it.second.season ?: 0) * 10000 + (it.second.episode ?: 0) }
            .map { it.second }
    }

    override suspend fun load(url: String): LoadResponse? {
        refreshDomain()
        val parts = payloadParts(url)
        if (parts.size < 3) return null
        val type = if (parts[0] == "tv") "tv" else "movie"
        val tmdbId = parts[1].toIntOrNull() ?: return null
        val fallbackTitle = parts.drop(2).joinToString("|")

        val detail = fetchDetail(type, tmdbId) ?: return null
        val title = (detail.title ?: detail.name)?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
        val poster = detail.posterPath?.let { TMDB_IMG + it }
        val background = detail.backdropPath?.let { TMDB_BACK + it }
        val genres = detail.genres.orEmpty().mapNotNull { it.name?.takeIf { g -> g.isNotBlank() } }
        val score = detail.voteAverage?.let { Score.from10(it.toInt()) }
        val isAnime = genres.any { it.equals("Animation", true) }

        return if (type == "movie") {
            newMovieLoadResponse(title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, "movie|$tmdbId|$title") {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = detail.overview
                this.tags = genres
                this.score = score
                this.duration = detail.runtime
            }
        } else {
            val episodes = episodesForShow(tmdbId, title, detail)
            if (episodes.isEmpty()) return null
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = detail.overview
                this.tags = genres
                this.score = score
                this.showStatus = when (detail.status) {
                    "Returning Series" -> ShowStatus.Ongoing
                    "Ended", "Canceled" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = payloadParts(data)
        val type = if (parts.getOrNull(0) == "tv") "tv" else "movie"
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return false

        val resolver = KdesaSources(mainUrl)
        return if (type == "movie") {
            resolver.resolveMovie(tmdbId, subtitleCallback, callback)
        } else {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            resolver.resolveShow(tmdbId, season, episode, subtitleCallback, callback)
        }
    }
}
