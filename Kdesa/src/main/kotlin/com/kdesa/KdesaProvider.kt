package com.kdesa

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.raghav.donation.DonationManager
import java.net.URLEncoder

private const val TAG = "Kdesa"

private const val EMBED_URL = "https://embed.vidrift.net"
private const val UA =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"
private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
private const val TMDB_BACK = "https://image.tmdb.org/t/p/original"

private fun siteHeaders(accept: String, referer: String? = null): Map<String, String> {
    val headers = mutableMapOf(
        "Accept" to accept,
        "User-Agent" to UA
    )
    if (referer != null) headers["Referer"] = referer
    return headers
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

// detail json is buried in the rsc stream, cut it out with a brace scan
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
    override var mainUrl = "https://7movies.ac"
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

    // the prefix keeps the framework from mangling the pipe separated payload
    private fun payloadParts(raw: String): List<String> {
        var s = raw.trim()
        if (s.startsWith("$mainUrl/")) s = s.removePrefix("$mainUrl/")
        return s.split("|")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val (type, genre) = request.data.split("|", limit = 2)
        val genreParam = genre.takeIf { it.isNotBlank() }?.let { "&genre=$it" } ?: ""
        return try {
            val response = app.get(
                "$mainUrl/api/discover?page=$page&type=$type$genreParam",
                headers = siteHeaders("*/*", "$mainUrl/"),
                timeout = 30_000L
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
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val response = app.get(
                "$mainUrl/api/search?v=5&q=$encoded",
                headers = siteHeaders("*/*", "$mainUrl/"),
                timeout = 30_000L
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
                timeout = 30_000L
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

    // names and stills live on the player's index, one call per season
    private suspend fun episodesForShow(tmdbId: Int, title: String, detail: DetailJson): List<Episode> {
        val seasonMap = try {
            val first = app.get(
                "$EMBED_URL/api/episodes/$tmdbId/1?v=2",
                headers = siteHeaders("*/*", "$mainUrl/"),
                timeout = 30_000L
            )
            parseJson<EpisodesResp>(first.text).seasons.orEmpty()
                .mapNotNull { s -> s.season?.let { it to (s.episodes ?: 0) } }
                .toMap()
        } catch (e: Exception) {
            Log.d(TAG, "episode index: ${e.message}")
            null
        }

        val seasons = seasonMap?.filterKeys { it > 0 && (seasonMap[it] ?: 0) > 0 }
            ?: detail.seasons.orEmpty().filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }
                .mapNotNull { s -> s.seasonNumber?.let { it to (s.episodeCount ?: 0) } }
                .toMap()
        if (seasons.isEmpty()) return numberedEpisodes(tmdbId, title, detail.seasons.orEmpty())

        val episodes = mutableListOf<Episode>()
        for ((season, count) in seasons.toSortedMap()) {
            val meta = try {
                app.get(
                    "$EMBED_URL/api/episodes/$tmdbId/$season?v=2",
                    headers = siteHeaders("*/*", "$mainUrl/"),
                    timeout = 30_000L
                ).let { parseJson<EpisodesResp>(it.text).episodes }
            } catch (e: Exception) {
                Log.d(TAG, "season $season: ${e.message}")
                null
            }
            if (meta.isNullOrEmpty()) {
                (1..count).forEach { ep ->
                    episodes.add(
                        newEpisode("tv|$tmdbId|$season|$ep|$title") {
                            this.season = season
                            this.episode = ep
                        }
                    )
                }
            } else {
                meta.forEach { ep ->
                    val number = ep.episode ?: return@forEach
                    episodes.add(
                        newEpisode("tv|$tmdbId|$season|$number|$title") {
                            this.name = ep.name?.takeIf { it.isNotBlank() }
                            this.season = season
                            this.episode = number
                            this.posterUrl = ep.still?.takeIf { it.isNotBlank() }
                            this.description = ep.overview?.takeIf { it.isNotBlank() }
                            this.runTime = ep.runtime
                        }
                    )
                }
            }
        }
        return episodes
    }

    override suspend fun load(url: String): LoadResponse? {
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

        val resolver = KdesaSources()
        return if (type == "movie") {
            resolver.resolveMovie(tmdbId, subtitleCallback, callback)
        } else {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            resolver.resolveShow(tmdbId, season, episode, subtitleCallback, callback)
        }
    }
}
