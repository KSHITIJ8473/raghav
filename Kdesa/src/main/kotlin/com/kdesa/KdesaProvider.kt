package com.kdesa

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLEncoder

private const val TAG = "Kdesa"

class KdesaProvider : MainAPI() {
    override var mainUrl = "https://kdesa.stream"
    override var name = "KDesa"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // kdesa.stream is a TMDB front-end, so the catalog comes straight from
    // the TMDB api. Bearer token from the site's config.js, public v3 key as fallback.
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbBearer =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJjYTg3NmZkYmVhMjNhMzI3ODY0ZjRjN2U5MzMwZTYxNiIsIm5iZiI6MTc4MjIwOTQ0NC45OTksInN1YiI6IjZhM2E1YmE0ZmMzZGFiNGNmYzMzNjIxMCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.WlSOswQDdxdbKu0jARJoruV6PlteoTXB1Oj4gRaibBI"
    private val tmdbApiKey = "1865f43a0549ca50d341dd9ab8b29f49"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBack = "https://image.tmdb.org/t/p/original"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "tmdb_trending" to "Trending",
        "tmdb_popular_movies" to "Popular Movies",
        "tmdb_popular_tv" to "Popular TV Shows",
        "tmdb_top_movies" to "Top Rated Movies",
        "tmdb_top_tv" to "Top Rated TV Shows",
        "tmdb_anime_movies" to "Anime Movies",
        "tmdb_anime_tv" to "Anime Series",
        "tmdb_airing_tv" to "Airing Now",
        "tmdb_now_playing" to "In Theaters",
    )

    private suspend fun tmdbGet(path: String, language: String = "en-US"): String {
        val sep = if (path.contains("?")) "&" else "?"
        val bearer = app.get(
            "$tmdbApi$path${sep}language=$language",
            headers = mapOf(
                "Authorization" to "Bearer $tmdbBearer",
                "Accept" to "application/json",
                "User-Agent" to ua
            ), timeout = 30_000L
        )
        if (bearer.code == 200 && !bearer.text.contains("Invalid API key")) {
            return bearer.text
        }
        Log.e(TAG, "tmdbGet bearer failed (${bearer.code}) for $path, falling back to api_key")
        return app.get(
            "$tmdbApi$path${sep}api_key=$tmdbApiKey&language=$language",
            headers = mapOf("Accept" to "application/json", "User-Agent" to ua),
            timeout = 30_000L
        ).text
    }

    data class TmdbItem(
        val id: Int?,
        val title: String?,
        val name: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("genre_ids") val genreIds: List<Int>?
    )

    data class TmdbResp(val page: Int?, val results: List<TmdbItem>?, @JsonProperty("total_pages") val totalPages: Int?)

    data class TmdbGenre(val id: Int, val name: String)
    data class TmdbDetail(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        val tagline: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val runtime: Int? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        val genres: List<TmdbGenre>? = null,
        val status: String? = null,
        val seasons: List<TmdbSeason>? = null,
        @JsonProperty("production_companies") val productionCompanies: List<ProdCompany>? = null
    ) {
        data class ProdCompany(val name: String?)
    }

    data class TmdbSeason(
        val id: Int,
        @JsonProperty("season_number") val seasonNumber: Int,
        val name: String?,
        @JsonProperty("episode_count") val episodeCount: Int?
    )

    data class TmdbSeasonDetail(
        val episodes: List<TmdbEpisode>?
    )

    data class TmdbEpisode(
        val id: Int,
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("season_number") val seasonNumber: Int,
        val name: String?,
        val overview: String?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("air_date") val airDate: String?,
        @JsonProperty("runtime") val runtime: Int?
    )

    // Only /trending/all returns media_type on every item; the single-type
    // endpoints leave it out, so the section passes its own type in.
    private fun TmdbItem.toSearch(defaultType: String? = null): SearchResponse? {
        val type = this.mediaType ?: defaultType ?: return null
        if (type != "movie" && type != "tv") return null
        val id = this.id ?: return null
        val t = if (type == "movie") this.title ?: this.name else this.name ?: this.title
        if (t.isNullOrBlank()) return null
        val poster = this.posterPath?.let { tmdbImg + it }
        val year = (this.releaseDate ?: this.firstAirDate)?.take(4)?.toIntOrNull()
        // the TMDB id rides in the payload; the absolute url keeps fixUrl from rewriting it
        return if (type == "movie") {
            newMovieSearchResponse(t, "$mainUrl/movie|$id|$t", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            val isAnime = (this.genreIds ?: emptyList()).contains(16)
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
            if (isAnime) {
                newAnimeSearchResponse(t, "$mainUrl/tv|$id|$t", tvType) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newMovieSearchResponse(t, "$mainUrl/tv|$id|$t", tvType) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    // Payloads look like movie|123|Title or tv|123|1|2|Title, optionally
    // prefixed with the site url - strip it so both forms work.
    private fun payloadParts(raw: String): List<String> {
        var s = raw.trim()
        if (s.startsWith("$mainUrl/")) s = s.removePrefix("$mainUrl/")
        return s.split("|")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            var path: String
            var defType: String? = null
            when (request.data) {
                "tmdb_trending" -> path = "/trending/all/day?page=$page"
                "tmdb_popular_movies" -> {
                    path = "/movie/popular?page=$page"; defType = "movie"
                }
                "tmdb_popular_tv" -> {
                    path = "/tv/popular?page=$page"; defType = "tv"
                }
                "tmdb_top_movies" -> {
                    path = "/movie/top_rated?page=$page"; defType = "movie"
                }
                "tmdb_top_tv" -> {
                    path = "/tv/top_rated?page=$page"; defType = "tv"
                }
                "tmdb_anime_movies" -> {
                    path = "/discover/movie?page=$page&with_genres=16&sort_by=popularity.desc&include_adult=false"; defType = "movie"
                }
                "tmdb_anime_tv" -> {
                    path = "/discover/tv?page=$page&with_genres=16&sort_by=popularity.desc&include_adult=false"; defType = "tv"
                }
                "tmdb_airing_tv" -> {
                    path = "/tv/on_the_air?page=$page"; defType = "tv"
                }
                "tmdb_now_playing" -> {
                    path = "/movie/now_playing?page=$page"; defType = "movie"
                }
                else -> return newHomePageResponse(request.name, emptyList())
            }
            val json = tmdbGet(path)
            val resp = parseJson<TmdbResp>(json)
            val items = resp.results?.mapNotNull { it.toSearch(defType) } ?: emptyList()
            Log.d(TAG, "getMainPage ${request.name} -> ${items.size} items")
            newHomePageResponse(request.name, items, resp.totalPages != null && page < (resp.totalPages ?: 1))
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage failed for ${request.name}: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val json = tmdbGet("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&page=1&include_adult=false")
            val resp = parseJson<TmdbResp>(json)
            val results = resp.results?.mapNotNull { it.toSearch() } ?: emptyList()
            Log.d(TAG, "search '$query' -> ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: $url")
        val parts = payloadParts(url)
        if (parts.size < 3) return null
        val type = parts[0]
        val tmdbId = parts[1].toIntOrNull() ?: return null
        val fallbackTitle = parts.drop(2).joinToString("|")

        return try {
            val detail = parseJson<TmdbDetail>(tmdbGet("/$type/$tmdbId"))
            val title = detail.title ?: detail.name ?: fallbackTitle
            val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
            val poster = detail.posterPath?.let { tmdbImg + it }
            val bg = detail.backdropPath?.let { tmdbBack + it }
            val genres = detail.genres?.mapNotNull { it.name } ?: emptyList()
            val score = detail.voteAverage?.let { Score.from10(it.toInt()) }

            if (type == "movie") {
                val data = "$mainUrl/movie|$tmdbId|$title"
                newMovieLoadResponse(title, url, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bg
                    this.year = year
                    this.plot = detail.overview
                    this.tags = genres
                    this.score = score
                    this.duration = detail.runtime
                    this.contentRating = null
                }
            } else {
                val seasons = detail.seasons?.filter { it.seasonNumber > 0 && (it.episodeCount ?: 0) > 0 } ?: emptyList()
                val episodes = mutableListOf<Episode>()
                for (season in seasons) {
                    try {
                        val seasonDetail = parseJson<TmdbSeasonDetail>(
                            tmdbGet("/tv/$tmdbId/season/${season.seasonNumber}")
                        )
                        seasonDetail.episodes?.forEach { ep ->
                            val epData = "$mainUrl/tv|$tmdbId|${ep.seasonNumber}|${ep.episodeNumber}|$title"
                            episodes.add(
                                newEpisode(epData) {
                                    this.name = ep.name
                                    this.season = ep.seasonNumber
                                    this.episode = ep.episodeNumber
                                    this.posterUrl = ep.stillPath?.let { tmdbImg + it }
                                    this.description = ep.overview
                                    this.runTime = ep.runtime
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "load: failed fetching season ${season.seasonNumber}: ${e.message}")
                    }
                }
                Log.d(TAG, "load: '$title' -> ${episodes.size} episodes")

                val isAnime = genres.any { it.equals("Animation", true) }
                val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bg
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
        } catch (e: Exception) {
            Log.e(TAG, "load failed for $url: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=$data")
        val parts = payloadParts(data)
        val type = parts.getOrNull(0) ?: return false
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return false

        val resolver = KdesaSources()
        var any = false

        if (type == "movie") {
            val title = parts.drop(2).joinToString("|")
            any = any or resolver.resolveMovie(tmdbId, title, subtitleCallback, callback)
        } else {
            val season = parts.getOrNull(2)?.toIntOrNull()
            val episode = parts.getOrNull(3)?.toIntOrNull()
            val title = parts.drop(4).joinToString("|")
            if (season == null || episode == null) {
                Log.e(TAG, "loadLinks: missing season/episode in data: $data")
                return false
            }
            any = any or resolver.resolveShow(tmdbId, title, season, episode, subtitleCallback, callback)
        }

        Log.d(TAG, "loadLinks: done, any=$any")
        return any
    }
}
