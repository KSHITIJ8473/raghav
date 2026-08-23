package com.laddu100.hdghartv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class HDGharTVProvider : MainAPI() {
    override var mainUrl = "https://hdghartv.cc"
    override var name = "HDGharTV"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val fallbackApiBase = "https://hdghartv.cc"
    private val TAG = "HDGharTV"

    private suspend fun apiBase(): String {
        val domain = FirebaseDomainHelper.getDomain("hdghartv")
        return (domain ?: fallbackApiBase).removeSuffix("/")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamLink(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("isActive") val isActive: Boolean? = null,
        @JsonProperty("headers") val headers: String? = null,
        @JsonProperty("userAgent") val userAgent: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Episode(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("episodes") val episodes: List<Episode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaItem(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("voteAverage") val voteAverage: Double? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaListResponse(
        @JsonProperty("data") val data: List<MediaItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("total") val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FeaturedItem(
        @JsonProperty("sourceId") val sourceId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSearchResponse(
        @JsonProperty("movies") val movies: List<MediaItem>? = null,
        @JsonProperty("series") val series: List<MediaItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val id: String,
        val type: String,
        val title: String,
        val posterUrl: String? = null
    )

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "series" to "Series",
        "featured" to "Featured"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val base = apiBase()
        var hasNext = false

        try {
            when (request.data) {
                "movies" -> {
                    val res = app.get("$base/api/movies/public?page=$page&limit=20", referer = "$base/")
                    val parsed = parseJson<MediaListResponse>(res.text)
                    parsed.totalPages?.let { hasNext = page < it }
                    val items = parsed.data?.mapNotNull { it.toSearchResponse("movie") } ?: emptyList()
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList("Movies", items, isHorizontalImages = true))
                    }
                }
                "series" -> {
                    val res = app.get("$base/api/series/public?page=$page&limit=20", referer = "$base/")
                    val parsed = parseJson<MediaListResponse>(res.text)
                    parsed.totalPages?.let { hasNext = page < it }
                    val items = parsed.data?.mapNotNull { it.toSearchResponse("series") } ?: emptyList()
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList("Series", items, isHorizontalImages = true))
                    }
                }
                "featured" -> {
                    val res = app.get("$base/api/featured/public", referer = "$base/")
                    val parsed = parseJson<List<FeaturedItem>>(res.text)
                    val items = parsed.mapNotNull { f ->
                        val id = f.sourceId ?: return@mapNotNull null
                        val title = f.title ?: return@mapNotNull null
                        val type = if (f.type == "series") "series" else "movie"
                        val loadData = LoadData(id = id, type = type, title = title, posterUrl = f.posterPath)
                        newMovieSearchResponse(title, loadData.toJson(), if (type == "series") TvType.TvSeries else TvType.Movie) {
                            this.posterUrl = f.posterPath
                        }
                    }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList("Featured", items, isHorizontalImages = true))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = hasNext)
    }

    private fun MediaItem.toSearchResponse(type: String): SearchResponse? {
        val id = id ?: return null
        val title = title ?: originalTitle ?: return null
        val loadData = LoadData(id = id, type = type, title = title, posterUrl = posterPath)
        return newMovieSearchResponse(title, loadData.toJson(), if (type == "series") TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterPath
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val base = apiBase()
        val results = mutableListOf<SearchResponse>()

        try {
            val res = app.get("$base/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", referer = "$base/")
            val parsed = parseJson<ApiSearchResponse>(res.text)
            parsed.movies?.forEach { m -> m.toSearchResponse("movie")?.let { results.add(it) } }
            parsed.series?.forEach { s -> s.toSearchResponse("series")?.let { results.add(it) } }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            return null
        }

        val base = apiBase()

        return try {
            when (loadData.type) {
                "movie" -> {
                    val res = app.get("$base/api/movies/public/${loadData.id}", referer = "$base/")
                    val movie = parseJson<MediaItem>(res.text)
                    val title = movie.title ?: movie.originalTitle ?: loadData.title
                    val streams = movie.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()

                    newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                        this.posterUrl = movie.posterPath ?: loadData.posterUrl
                        this.backgroundPosterUrl = movie.backdropPath
                        this.plot = movie.overview
                        this.year = movie.releaseDate?.substring(0, 4)?.toIntOrNull()
                        this.tags = movie.genres?.mapNotNull { it.name } ?: emptyList()
                    }
                }
                "series" -> {
                    val res = app.get("$base/api/series/public/${loadData.id}", referer = "$base/")
                    val series = parseJson<MediaItem>(res.text)
                    val title = series.title ?: series.originalTitle ?: loadData.title

                    val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                    series.seasons?.forEach { season ->
                        val seasonNum = season.seasonNumber ?: return@forEach
                        season.episodes?.forEach { ep ->
                            val epNum = ep.episodeNumber ?: return@forEach
                            val streams = ep.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()
                            val epData = streams.toJson()

                            episodes.add(
                                newEpisode(epData) {
                                    this.name = ep.name ?: "Episode $epNum"
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = ep.stillPath
                                    this.description = ep.overview
                                }
                            )
                        }
                    }

                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = series.posterPath ?: loadData.posterUrl
                        this.backgroundPosterUrl = series.backdropPath
                        this.plot = series.overview
                        this.year = series.firstAirDate?.substring(0, 4)?.toIntOrNull()
                        this.tags = series.genres?.mapNotNull { it.name } ?: emptyList()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streams = try {
            parseJson<List<StreamLink>>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            return false
        }

        if (streams.isEmpty()) return false

        val base = apiBase()
        var found = false

        for (stream in streams) {
            val url = stream.url ?: continue
            if (url.isBlank()) continue

            val qualityName = stream.quality ?: "Unknown"
            val language = stream.language?.trim()
            val linkName = if (language.isNullOrEmpty()) qualityName else "$qualityName ($language)"

            val quality = when (stream.quality?.lowercase()) {
                "4k", "2160p" -> Qualities.P2160.value
                "1080p", "fhd" -> Qualities.P1080.value
                "720p", "hd" -> Qualities.P720.value
                "480p", "sd" -> Qualities.P480.value
                "360p" -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            val type = when {
                url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                url.contains(".mp4", ignoreCase = true) || url.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                else -> INFER_TYPE
            }

            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Referer" to "$base/"
            )
            if (stream.headers?.isNotBlank() == true) {
                try {
                    val extraHeaders = parseJson<Map<String, String>>(stream.headers)
                    headers.putAll(extraHeaders)
                } catch (_: Exception) {}
            }
            if (stream.userAgent?.isNotBlank() == true) {
                headers["User-Agent"] = stream.userAgent
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = linkName,
                    url = url,
                    type = type
                ) {
                    this.quality = quality
                    this.headers = headers
                }
            )
            found = true
        }

        return found
    }
}
