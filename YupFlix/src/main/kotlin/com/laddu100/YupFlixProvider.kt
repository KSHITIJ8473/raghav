package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class YupFlixProvider : MainAPI() {
    override var mainUrl = "https://watch.yupflix.org"
    override var name = "YupFlix"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://jolly-mouse-f41c.annierane.workers.dev"
    private val TAG = "YupFlix"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MovieListResponse(
        @JsonProperty("data") val data: List<ContentItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(
        @JsonProperty("movies") val movies: List<ContentItem>? = null,
        @JsonProperty("series") val series: List<ContentItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ContentItem(
        @JsonProperty("_id") val id: String,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("contentType") val contentType: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(@JsonProperty("name") val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MovieDetail(
        @JsonProperty("_id") val id: String,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeriesDetail(
        @JsonProperty("_id") val id: String,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamLink(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("isActive") val isActive: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val title: String,
        val streamLinks: List<StreamLink>,
        val posterUrl: String? = null,
        val isSeries: Boolean = false
    )

    private fun ContentItem.getType(): String = type ?: contentType ?: "movie"

    private fun ContentItem.getYear(): Int? = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

    private fun ContentItem.getPoster(): String? = posterPath ?: backdropPath

    override val mainPage = mainPageOf(
        "$apiUrl/api/movies/public" to "All"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        try {
            coroutineScope {
                val latest = async { fetchMovies("sort=latest", 30) }
                val bollywood = async { fetchMovies("category=Bollywood", 30) }
                val hollywood = async { fetchMovies("category=Hollywood", 30) }
                val action = async { fetchMovies("genre=Action", 30) }
                val comedy = async { fetchMovies("genre=Comedy", 30) }
                val drama = async { fetchMovies("genre=Drama", 30) }

                latest.await().takeIf { it.isNotEmpty() }?.let {
                    lists.add(HomePageList("Latest", it, isHorizontalImages = true))
                }
                bollywood.await().takeIf { it.isNotEmpty() }?.let {
                    lists.add(HomePageList("Bollywood", it, isHorizontalImages = true))
                }
                hollywood.await().takeIf { it.isNotEmpty() }?.let {
                    lists.add(HomePageList("Hollywood", it, isHorizontalImages = true))
                }
                action.await().takeIf { it.isNotEmpty() }?.let {
                    lists.add(HomePageList("Action", it, isHorizontalImages = true))
                }
                comedy.await().takeIf { it.isNotEmpty() }?.let {
                    lists.add(HomePageList("Comedy", it, isHorizontalImages = true))
                }
                drama.await().takeIf { it.isNotEmpty() }?.let {
                    lists.add(HomePageList("Drama", it, isHorizontalImages = true))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    private suspend fun fetchMovies(params: String, limit: Int): List<SearchResponse> {
        return try {
            val url = "$apiUrl/api/movies/public?$params&page=1&limit=$limit"
            val parsed = parseJson<MovieListResponse>(app.get(url, timeout = 30_000L).text)
            parsed.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun ContentItem.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val contentType = getType()
        val tvType = if (contentType == "series") TvType.TvSeries else TvType.Movie
        val data = "$mainUrl|$id|$contentType"
        return newMovieSearchResponse(title, data, tvType) {
            this.posterUrl = getPoster()
            this.year = getYear()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/api/search?q=$encoded&limit=20"
            val parsed = parseJson<SearchApiResponse>(app.get(url, timeout = 30_000L).text)

            val results = mutableListOf<SearchResponse>()
            parsed.movies?.forEach { results.add(it.toSearchResponse() ?: return@forEach) }
            parsed.series?.forEach {
                it.copy(contentType = "series").toSearchResponse()?.let { sr -> results.add(sr) }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size < 3) return null
        val contentId = parts[1]
        val contentType = parts[2]
        val isSeries = contentType == "series"

        return try {
            if (isSeries) loadSeries(contentId) else loadMovie(contentId)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        val url = "$apiUrl/api/movies/public/$id"
        val movie = parseJson<MovieDetail>(app.get(url, timeout = 30_000L).text)
        val title = movie.title ?: return null
        val poster = movie.posterPath ?: movie.backdropPath
        val year = movie.releaseDate?.take(4)?.toIntOrNull()
        val genres = movie.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
        val streamLinks = movie.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()

        val loadData = LoadData(title = title, streamLinks = streamLinks, posterUrl = poster, isSeries = false)

        return newMovieLoadResponse(title, "$mainUrl|$id|movie", TvType.Movie, loadData.toJson()) {
            this.posterUrl = poster
            this.plot = movie.overview
            this.year = year
            this.tags = genres
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        val url = "$apiUrl/api/series/public/$id"
        val series = parseJson<SeriesDetail>(app.get(url, timeout = 30_000L).text)
        val title = series.title ?: return null
        val poster = series.posterPath ?: series.backdropPath
        val year = series.firstAirDate?.take(4)?.toIntOrNull()
        val genres = series.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }

        val episodes = mutableListOf<Episode>()
        series.seasons?.forEach { season ->
            val seasonNum = season.seasonNumber ?: 1
            season.episodes?.forEach { ep ->
                val epNum = ep.episodeNumber ?: return@forEach
                val epTitle = ep.name ?: "Episode $epNum"
                val streamLinks = ep.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()
                if (streamLinks.isEmpty()) return@forEach

                val loadData = LoadData(
                    title = "$title S${seasonNum}E$epNum",
                    streamLinks = streamLinks,
                    posterUrl = ep.stillPath ?: poster,
                    isSeries = true
                )
                episodes.add(newEpisode(loadData.toJson()) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = ep.stillPath
                })
            }
        }

        return newTvSeriesLoadResponse(title, "$mainUrl|$id|series", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = series.overview
            this.year = year
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = try {
            parseJson<LoadData>(data)
        } catch (e: Exception) {
            return false
        }

        if (loadData.streamLinks.isEmpty()) return false

        val playHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        )

        var found = false
        for (link in loadData.streamLinks) {
            val url = link.url ?: continue
            val qualityStr = link.quality ?: "Unknown"
            val quality = when {
                qualityStr.contains("1080") -> Qualities.P1080.value
                qualityStr.contains("720") -> Qualities.P720.value
                qualityStr.contains("480") -> Qualities.P480.value
                qualityStr.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            try {
                callback.invoke(
                    newExtractorLink(
                        source = "$name $qualityStr",
                        name = "$name $qualityStr",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = playHeaders
                    }
                )
                found = true
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: $qualityStr failed: ${e.message}")
            }
        }

        return found
    }
}
