package com.movy

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Movy"

class MovyProvider : MainAPI() {
    override var mainUrl = MovyApi.MAIN_URL
    override var name = "Movy"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending TV Shows",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated TV Shows",
        "movie/now_playing" to "In Theaters",
        "discover/tv?with_genres=16&sort_by=popularity.desc" to "Anime Series",
        "discover/movie?with_genres=16&sort_by=popularity.desc" to "Anime Movies",
        "discover/movie?with_genres=28&sort_by=popularity.desc&vote_count.gte=300" to "Action Movies",
        "discover/movie?with_genres=35&sort_by=popularity.desc&vote_count.gte=300" to "Comedy Movies",
        "discover/movie?with_genres=27&sort_by=popularity.desc&vote_count.gte=300" to "Horror Movies",
        "discover/movie?with_genres=878&sort_by=popularity.desc&vote_count.gte=300" to "Sci-Fi Movies",
    )

    private fun itemYear(item: MovyTmdbItem): Int? =
        (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()

    private fun itemTitle(item: MovyTmdbItem): String? =
        (item.title ?: item.name)?.takeIf { it.isNotBlank() }

    private fun itemToSearch(item: MovyTmdbItem, fallbackType: String): SearchResponse? {
        val id = item.id ?: return null
        val title = itemTitle(item) ?: return null
        val kind = item.mediaType ?: fallbackType
        val poster = MovyApi.poster(item.posterPath, "w342")
        val year = itemYear(item)
        return if (kind == "tv") {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val data = MovyApi.tmdbPage(request.data, page)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val fallbackType = if (request.data.startsWith("tv/") ||
            request.data.startsWith("discover/tv")
        ) "tv" else "movie"
        val list = data.results.orEmpty().mapNotNull { itemToSearch(it, fallbackType) }
        val hasNext = page < (data.totalPages ?: 1).coerceAtMost(500)
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val data = MovyApi.searchMulti(query, 1) ?: return emptyList()
        return data.results.orEmpty()
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .mapNotNull { itemToSearch(it, "movie") }
    }

    private fun germanTitle(translations: MovyTranslations?, fallback: String?): String? {
        val list = translations?.translations ?: return null
        val de = list.firstOrNull { it.iso == "de" }?.data ?: return null
        val title = (de.title ?: de.name)?.takeIf { it.isNotBlank() } ?: return null
        if (title == fallback) return null
        return title
    }

    private fun watchData(
        kind: String,
        id: Int,
        title: String,
        year: Int?,
        imdbId: String?,
        german: String?,
        season: Int? = null,
        episode: Int? = null,
        totalSeasons: Int? = null,
    ): String {
        val obj = JSONObject()
        obj.put("k", kind)
        obj.put("i", id)
        obj.put("t", title)
        obj.put("y", year ?: 0)
        obj.put("m", imdbId.orEmpty())
        obj.put("g", german.orEmpty())
        if (season != null) obj.put("s", season)
        if (episode != null) obj.put("e", episode)
        if (totalSeasons != null) obj.put("ts", totalSeasons)
        return obj.toString()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        return if (url.contains("/tv/")) loadTv(id, url) else loadMovie(id, url)
    }

    private suspend fun loadMovie(id: Int, url: String): LoadResponse? {
        val details = MovyApi.movieDetails(id) ?: return null
        val title = details.title?.takeIf { it.isNotBlank() } ?: return null
        val year = details.releaseDate?.take(4)?.toIntOrNull()
        val imdbId = details.imdbId ?: details.externalIds?.imdbId
        val german = germanTitle(details.translations, title)
        val anime = details.genres.orEmpty().any { it.id == 16 }
        val data = watchData("movie", id, title, year, imdbId, german)
        return newMovieLoadResponse(title, url, if (anime) TvType.AnimeMovie else TvType.Movie, data) {
            posterUrl = MovyApi.poster(details.posterPath)
            backgroundPosterUrl = MovyApi.poster(details.backdropPath, "w780")
            this.year = year
            plot = details.overview
            tags = details.genres.orEmpty().mapNotNull { it.name }
            duration = details.runtime
            details.voteAverage?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
        }
    }

    private suspend fun loadTv(id: Int, url: String): LoadResponse? {
        val details = MovyApi.tvDetails(id) ?: return null
        val title = details.name?.takeIf { it.isNotBlank() } ?: return null
        val year = details.firstAirDate?.take(4)?.toIntOrNull()
        val imdbId = details.externalIds?.imdbId
        val german = germanTitle(details.translations, title)
        val seasons = details.seasons.orEmpty()
            .filter { (it.seasonNumber ?: 0) >= 1 && (it.episodeCount ?: 0) > 0 }

        val seasonData: List<MovySeasonDetails?> = coroutineScope {
            seasons.map { season -> async { MovyApi.seasonDetails(id, season.seasonNumber ?: 1) } }
                .awaitAll()
        }

        val episodes = mutableListOf<Episode>()
        seasons.forEachIndexed { index, season ->
            val seasonNumber = season.seasonNumber ?: return@forEachIndexed
            val entries = seasonData.getOrNull(index)?.episodes.orEmpty()
            if (entries.isEmpty()) {
                Log.d(TAG, "season $seasonNumber of $id came back empty")
            }
            entries.forEach { entry ->
                val number = entry.episodeNumber ?: return@forEach
                val data = watchData(
                    "tv", id, title, year, imdbId, german,
                    season = seasonNumber, episode = number, totalSeasons = seasons.size
                )
                episodes.add(
                    newEpisode(data) {
                        this.name = entry.name?.takeIf { it.isNotBlank() }
                        this.episode = number
                        this.season = seasonNumber
                        this.description = entry.overview
                        this.posterUrl = MovyApi.poster(entry.stillPath, "w300")
                    }
                )
            }
        }
        if (episodes.isEmpty()) return null

        val anime = details.genres.orEmpty().any { it.id == 16 }
        val showStatus = when (details.status?.lowercase()) {
            "returning series" -> ShowStatus.Ongoing
            "ended", "canceled", "cancelled" -> ShowStatus.Completed
            else -> null
        }
        return newTvSeriesLoadResponse(title, url, if (anime) TvType.Anime else TvType.TvSeries, episodes) {
            posterUrl = MovyApi.poster(details.posterPath)
            backgroundPosterUrl = MovyApi.poster(details.backdropPath, "w780")
            this.year = year
            plot = details.overview
            tags = details.genres.orEmpty().mapNotNull { it.name }
            details.voteAverage?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            showStatus?.let { this.showStatus = it }
        }
    }

    private fun qualityValue(quality: String): Int? = when (quality.lowercase()) {
        "2160p", "4k" -> Qualities.P2160.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p", "sd" -> Qualities.P360.value
        else -> null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return false
        }
        val kind = obj.optString("k")
        val tmdbId = obj.optInt("i", 0).toString()
        if (tmdbId == "0") return false
        val request = MovyApi.SourcesRequest(
            mediaType = if (kind == "tv") "tv" else "movie",
            tmdbId = tmdbId,
            imdbId = obj.optString("m"),
            title = obj.optString("t"),
            year = obj.optInt("y", 0).toString(),
            season = obj.optInt("s", 1),
            episode = obj.optInt("e", 1),
            germanTitle = obj.optString("g").takeIf { it.isNotBlank() },
        )
        val pool = MovyApi.SeedPool(tmdbId)

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubLabels = ConcurrentHashMap.newKeySet<String>()
        val anyLink = AtomicBoolean(false)

        suspend fun emitSubtitles(city: MovyApi.City, reply: MovySourcesReply) {
            reply.subtitles.orEmpty().forEach { sub ->
                val url = sub.url?.takeIf { it.startsWith("http") } ?: return@forEach
                // raw relay links without a format tag are dead placeholders on
                // a couple of servers
                val format = sub.type?.lowercase() ?: return@forEach
                if (format != "vtt" && format != "srt") return@forEach
                var label = sub.language?.takeIf { it.isNotBlank() } ?: sub.lang ?: return@forEach
                // tampa style tracks hide behind bare two letter codes and point
                // at dead relay tokens, real tracks are full language names
                // (chinese shows up as "CMN - [Cmn] 7", so check before trimming)
                if (label.length < 4) return@forEach
                val cut = label.indexOf(" - [")
                if (cut > 0) label = label.substring(0, cut).trim()
                if (!seenSubUrls.add(url)) return@forEach
                if (!seenSubLabels.add(label.lowercase())) {
                    label = "$label (${city.display})"
                }
                try {
                    subtitleCallback.invoke(newSubtitleFile(label, url) {
                        headers = MovyApi.playHeaders
                    })
                } catch (e: Exception) {
                    Log.d(TAG, "subtitle emit: ${e.message}")
                }
            }
        }

        suspend fun emitLinks(city: MovyApi.City, reply: MovySourcesReply) {
            reply.sources.orEmpty().forEach { source ->
                val url = source.url?.takeIf { it.startsWith("http") } ?: return@forEach
                val qualityLabel = source.quality.orEmpty().trim()
                if (qualityLabel.isEmpty()) return@forEach
                // austin only keeps the english row, delhi carries the rest of
                // the indian languages
                if (city.slug == "austin" && qualityLabel != "English") return@forEach
                if (!seenLinks.add(url)) return@forEach
                val label = buildString {
                    append(city.display)
                    city.langNote?.let { append(' ').append(it) }
                    append(' ').append(qualityLabel)
                }
                val type = if (url.contains("/mp4/")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                try {
                    callback.invoke(
                        newExtractorLink(city.display, label, url, type) {
                            qualityValue(qualityLabel)?.let { quality = it }
                            referer = MovyApi.REFERER
                            headers = MovyApi.playHeaders
                        }
                    )
                    anyLink.set(true)
                } catch (e: Exception) {
                    Log.d(TAG, "link emit: ${e.message}")
                }
            }
        }

        coroutineScope {
            MovyApi.cities.chunked(6).forEach { wave ->
                wave.map { city ->
                    async {
                        val reply = MovyApi.citySources(city, request, pool)
                        if (reply != null) {
                            emitSubtitles(city, reply)
                            emitLinks(city, reply)
                        }
                    }
                }.awaitAll()
            }
        }
        return anyLink.get()
    }
}
