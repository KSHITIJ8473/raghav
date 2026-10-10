package com.justplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayLinkData(
    val id: Int? = null,
    val imdbId: String? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val orgTitle: String? = null,
    val year: Int? = null,
    val showYear: Int? = null,
    val isMovie: Boolean = false
) {

    val matchYear: Int?
        get() = if (season != null) (showYear ?: year) else year
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayTmdbData(
    val id: Int? = null,
    val type: String? = null
)

class JustPlay : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "JustPlay"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    companion object {
        private const val TMDB = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_KEY_ALT = "98ae14df2b8d8f8f8136499daf79f0e0"

        private const val KEY_DL_ONLY = "JUSTPLAY_DL_ONLY"
        private const val KEY_STREAM_ONLY = "JUSTPLAY_STREAM_ONLY"
        private const val KEY_CONCURRENCY = "JUSTPLAY_CONCURRENCY"
        private const val LINKS_PER_SOURCE = 12

        // the app cancels loadLinks after 120s, every site gets cut off before that
        private const val SITE_BUDGET_MS = 90_000L

        private fun widenClient() {
            try {
                val dispatcher = app.baseClient.dispatcher
                if (dispatcher.maxRequestsPerHost < 12) dispatcher.maxRequestsPerHost = 12
                if (dispatcher.maxRequests < 96) dispatcher.maxRequests = 96
            } catch (_: Exception) {
            }
        }

        fun tmdbImageUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/original$path"
        }

        fun siteEnabled(id: String): Boolean = try {
            CloudStreamApp.getKey<Boolean>("JUSTPLAY_SITE_$id") ?: true
        } catch (_: Exception) {
            true
        }

        data class SiteRow(val id: String, val label: String, val sub: String)

        val sites = listOf(
            SiteRow("netnaija", "NetNaija", "Direct mp4 streams"),
            SiteRow("vegamovies", "VegaMovies", "Dual audio movies and series"),
            SiteRow("hdhub4u", "HDHub4u", "Movies and series"),
            SiteRow("4khdhub", "4KHDHub", "UHD movies and packs"),
            SiteRow("themoviesflix", "TheMoviesFlix", "Movies and web series"),
            SiteRow("multimovies", "Multimovies", "Streaming servers"),
            SiteRow("movies4u", "Movies4u", "Movies and series"),
            SiteRow("moviesdrive", "MoviesDrive", "Movies and series up to 4K"),
            SiteRow("hindmoviez", "HindMoviez", "Hindi movies and series"),
            SiteRow("netmirror", "NetMirror", "Netflix, Hotstar and Prime Video mirrors")
        )

        fun downloadOnlyEnabled(): Boolean = try {
            CloudStreamApp.getKey<Boolean>(KEY_DL_ONLY) ?: false
        } catch (_: Exception) {
            false
        }

        fun streamOnlyEnabled(): Boolean = try {
            CloudStreamApp.getKey<Boolean>(KEY_STREAM_ONLY) ?: true
        } catch (_: Exception) {
            true
        }

        fun siteConcurrency(): Int = try {
            (CloudStreamApp.getKey<Int>(KEY_CONCURRENCY) ?: 6).coerceIn(2, 12)
        } catch (_: Exception) {
            6
        }

        fun setConcurrency(value: Int) {
            try {
                CloudStreamApp.setKey(KEY_CONCURRENCY, value.coerceIn(2, 12))
            } catch (_: Exception) {}
        }

        fun setDownloadOnly(on: Boolean) {
            try {
                CloudStreamApp.setKey(KEY_DL_ONLY, on)
            } catch (_: Exception) {}
        }

        fun setStreamOnly(on: Boolean) {
            try {
                CloudStreamApp.setKey(KEY_STREAM_ONLY, on)
            } catch (_: Exception) {}
        }
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val query = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        return try {
            JSONObject(app.get("$TMDB$path?api_key=$TMDB_KEY&$query", timeout = 12000L).text)
        } catch (e: Exception) {
            try {
                JSONObject(app.get("$TMDB$path?api_key=$TMDB_KEY_ALT&$query", timeout = 12000L).text)
            } catch (_: Exception) {
                null
            }
        }
    }

    override val mainPage = mainPageOf(
        Pair("/trending/all/day", "Trending Now"),
        Pair("/movie/popular", "Popular Movies"),
        Pair("/tv/popular", "Popular TV Shows"),
        Pair("/movie/top_rated", "Top Rated Movies"),
        Pair("/tv/top_rated", "Top Rated TV Shows"),
        Pair("/tv/airing_today", "Airing Today"),
        Pair("/discover/tv&with_networks=213", "Netflix"),
        Pair("/discover/tv&with_networks=1024", "Amazon Prime"),
        Pair("/discover/movie&with_origin_country=IN&sort_by=popularity.desc", "Indian Movies"),
        Pair("/discover/tv&with_origin_country=IN&sort_by=popularity.desc", "Indian Web Series"),
        Pair("/discover/tv&with_original_language=ko&sort_by=popularity.desc", "Korean Shows"),
        Pair("/discover/tv&with_genres=16&with_original_language=ja&sort_by=popularity.desc", "Anime Series")
    )

    private fun toSearchResponse(item: JSONObject, fallbackType: String): SearchResponse? {
        val id = item.optInt("id", 0)
        if (id == 0) return null
        val mediaType = item.optString("media_type").ifBlank { fallbackType }
        if (mediaType != "movie" && mediaType != "tv") return null
        val title = item.optString("title").ifBlank { item.optString("name") }
        if (title.isBlank()) return null
        val poster = tmdbImageUrl(item.optString("poster_path"))
        val year = item.optString("release_date").ifBlank { item.optString("first_air_date") }.take(4).toIntOrNull()
        val type = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, PlayTmdbData(id, mediaType).toJson(), type) {
            this.posterUrl = poster
            this.year = year
            score = Score.from10(item.optDouble("vote_average", 0.0))
        }
    }

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        // netmirror needs a ~1 min cookie solve on cold start, warm it up before anything is opened
        prefetchScope.launch { NetMirrorSite.prefetch() }
        val parts = request.data.split("&", limit = 2)
        val path = parts[0]
        val extra = parts.getOrNull(1)?.split("&")?.mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        } ?: emptyList()
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val params = (extra + listOf("language" to "en-US", "page" to "$page")).toMap()
        val json = tmdbGet(path, params)
        val results = json?.optJSONArray("results")
        val home = mutableListOf<SearchResponse>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                toSearchResponse(item, if (path.contains("trending")) "" else type)?.let { home.add(it) }
            }
        }
        val hasNext = (json?.optInt("page", 1) ?: 1) < (json?.optInt("total_pages", 1) ?: 1)
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        prefetchScope.launch { NetMirrorSite.prefetch() }
        val json = tmdbGet(
            "/search/multi",
            mapOf("query" to query, "include_adult" to "false", "page" to "1", "language" to "en-US")
        )
        val results = json?.optJSONArray("results") ?: return null
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            toSearchResponse(item, "")?.let { items.add(it) }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        prefetchScope.launch { NetMirrorSite.prefetch() }
        val data = try {
            parseJson<PlayTmdbData>(url)
        } catch (_: Exception) {
            return null
        }
        val id = data.id ?: return null
        val type = data.type ?: return null
        val isMovie = type == "movie"

        val detail = tmdbGet(
            "/$type/$id",
            mapOf(
                "language" to "en-US",
                "append_to_response" to "external_ids,credits,videos,recommendations"
            )
        ) ?: return null

        val title = detail.optString("title").ifBlank { detail.optString("name") }
        if (title.isBlank()) return null
        val orgTitle = detail.optString("original_title").ifBlank { detail.optString("original_name") }
        val releaseDate = detail.optString("release_date").ifBlank { detail.optString("first_air_date") }
        val year = releaseDate.take(4).toIntOrNull()
        val poster = tmdbImageUrl(detail.optString("poster_path"))
        val backdrop = tmdbImageUrl(detail.optString("backdrop_path"))
        val overview = detail.optString("overview")
        val runtime = detail.optInt("runtime", 0).takeIf { it > 0 }
        val imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }

        val genres = mutableListOf<String>()
        detail.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }

        val actors = mutableListOf<ActorData>()
        detail.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 12)) {
                val c = arr.optJSONObject(i) ?: continue
                val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
                actors.add(ActorData(Actor(name, tmdbImageUrl(c.optString("profile_path")))))
            }
        }

        val trailers = mutableListOf<TrailerData>()
        detail.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                if (v.optString("type") == "Trailer" && v.optString("site") == "YouTube") {
                    trailers.add(TrailerData("https://www.youtube.com/watch?v=${v.optString("key")}", null, false))
                }
            }
        }

        val recommendations = mutableListOf<SearchResponse>()
        detail.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 12)) {
                val item = arr.optJSONObject(i) ?: continue
                toSearchResponse(item, type)?.let { recommendations.add(it) }
            }
        }

        val rating = detail.optDouble("vote_average", 0.0)

        if (isMovie) {
            val linkData = PlayLinkData(
                id = id,
                imdbId = imdbId,
                type = type,
                title = title,
                orgTitle = orgTitle.ifBlank { null },
                year = year,
                isMovie = true
            )
            return newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.duration = runtime
                this.score = Score.from10(rating)
                this.tags = genres
                this.actors = actors
                this.recommendations = recommendations
                this.trailers = trailers
            }
        }

        val seasonsArr = detail.optJSONArray("seasons")
        val episodes = mutableListOf<Episode>()
        val seasons = mutableListOf<Pair<Int, String>>()
        if (seasonsArr != null) {
            for (i in 0 until seasonsArr.length()) {
                val s = seasonsArr.optJSONObject(i) ?: continue
                val num = s.optInt("season_number", -1)
                if (num < 0) continue
                seasons.add(num to s.optString("air_date"))
            }
        }
        if (seasons.isEmpty()) seasons.add(1 to "")

        coroutineScope {
            val semaphore = Semaphore(8)
            val deferred = seasons.map { (seasonNum, _) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val seasonJson = tmdbGet(
                                "/tv/$id/season/$seasonNum",
                                mapOf("language" to "en-US")
                            ) ?: return@withPermit emptyList()
                            val epsArr = seasonJson.optJSONArray("episodes") ?: return@withPermit emptyList()
                            (0 until epsArr.length()).mapNotNull { i ->
                                val ep = epsArr.optJSONObject(i) ?: return@mapNotNull null
                                try {
                                    val epNum = ep.optInt("episode_number", 0)
                                    if (epNum <= 0) return@mapNotNull null
                                    val airDate = ep.optString("air_date")
                                    val linkData = PlayLinkData(
                                        id = id,
                                        imdbId = imdbId,
                                        type = type,
                                        season = seasonNum,
                                        episode = epNum,
                                        title = title,
                                        orgTitle = orgTitle.ifBlank { null },
                                        year = airDate.take(4).toIntOrNull() ?: year,
                                        showYear = year,
                                        isMovie = false
                                    )
                                    newEpisode(linkData.toJson()) {
                                        this.name = ep.optString("name").ifBlank { "Episode $epNum" }
                                        this.season = seasonNum
                                        this.episode = epNum
                                        this.posterUrl = tmdbImageUrl(ep.optString("still_path"))
                                        this.description = ep.optString("overview")
                                        score = Score.from10(ep.optDouble("vote_average", 0.0))
                                        if (airDate.length >= 8 && airDate[0].isDigit()) {
                                            runCatching { addDate(airDate) }
                                        }
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            }
            deferred.forEach { d ->
                runCatching { episodes.addAll(d.await()) }
            }
        }

        episodes.sortBy { (it.season ?: 0) * 10000 + (it.episode ?: 0) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = overview
            this.score = Score.from10(rating)
            this.tags = genres
            this.actors = actors
            this.recommendations = recommendations
            this.trailers = trailers
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = try {
            parseJson<PlayLinkData>(data)
        } catch (_: Exception) {
            return false
        }

        data class SiteEntry(
            val id: String,
            val invoke: suspend (PlayLinkData, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit
        )

        val allSites = listOf(
            SiteEntry("netnaija") { r, s, c -> NetNaijaSite.invoke(r, s, c) },
            SiteEntry("vegamovies") { r, s, c -> VegaMoviesSite.invoke(r, s, c) },
            SiteEntry("hdhub4u") { r, s, c -> HdHub4uSite.invoke(r, s, c) },
            SiteEntry("4khdhub") { r, s, c -> FourKhdHubSite.invoke(r, s, c) },
            SiteEntry("themoviesflix") { r, s, c -> TmfSite.invoke(r, s, c) },
            SiteEntry("multimovies") { r, s, c -> MultimoviesSite.invoke(r, s, c) },
            SiteEntry("movies4u") { r, s, c -> Movies4uSite.invoke(r, s, c) },
            SiteEntry("moviesdrive") { r, s, c -> MoviesDriveSite.invoke(r, s, c) },
            SiteEntry("hindmoviez") { r, s, c -> HindMoviezSite.invoke(r, s, c) },
            SiteEntry("netmirror") { r, s, c -> NetMirrorSite.invoke(r, s, c) }
        )

        val active = allSites.filter { siteEnabled(it.id) }
        if (active.isEmpty()) return false

        val dlOnly = downloadOnlyEnabled()
        val streamOnly = streamOnlyEnabled() || !dlOnly

        widenClient()

        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val sourceCounts = ConcurrentHashMap<String, AtomicInteger>()

        val guardedCallback: (ExtractorLink) -> Unit = label@{ link ->
            val isDownload = PlaySourceFilter.isDownloadOnlyName(link.name)
            val allowed = if (isDownload) dlOnly else streamOnly
            if (!allowed) return@label
            if (!seenUrls.add(link.url)) return@label
            val emitted = sourceCounts.computeIfAbsent(link.source) { AtomicInteger() }
            if (emitted.incrementAndGet() > LINKS_PER_SOURCE) return@label
            callback(if (isDownload) PlaySourceFilter.taggedDownloadOnly(link) else link)
        }

        coroutineScope {
            val gate = Semaphore(siteConcurrency())
            active.forEach { site ->
                async(Dispatchers.IO) {
                    // the budget also covers the permit wait so a queued site never overruns the app's cancel
                    try {
                        withTimeoutOrNull(SITE_BUDGET_MS) {
                            gate.withPermit {
                                site.invoke(res, subtitleCallback, guardedCallback)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return true
    }
}
