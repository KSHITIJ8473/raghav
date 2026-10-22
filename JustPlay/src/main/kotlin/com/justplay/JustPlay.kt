package com.justplay

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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var lang = "en"

    companion object {
        private const val KEY_DL_ONLY = "JUSTPLAY_DL_ONLY"
        private const val KEY_STREAM_ONLY = "JUSTPLAY_STREAM_ONLY"
        private const val KEY_CONCURRENCY = "JUSTPLAY_CONCURRENCY"
        private const val KEY_LIVE_SPORTS = "JUSTPLAY_LIVE_SPORTS"
        private const val KEY_LIVE_SPORTS_TOP = "JUSTPLAY_LIVE_SPORTS_TOP"
        private const val LIVE_ROW = "live_sports"
        private const val LINKS_PER_SOURCE = 12

        // the app cancels loadLinks after 120s, every site gets cut off before that
        private const val SITE_BUDGET_MS = 90_000L

        private fun widenClient() {
            try {
                val dispatcher = app.baseClient.dispatcher
                // live sports resolve beside site scrapes, both need room in the queue
                if (dispatcher.maxRequestsPerHost < 40) dispatcher.maxRequestsPerHost = 40
                if (dispatcher.maxRequests < 200) dispatcher.maxRequests = 200
            } catch (_: Exception) {
            }
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
            SiteRow("netmirror", "NetMirror", "Netflix, Hotstar and Prime Video mirrors"),
            SiteRow("hicine", "HiCine", "Dual audio movies and series")
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
            (CloudStreamApp.getKey<Int>(KEY_CONCURRENCY) ?: 15).coerceIn(6, 40)
        } catch (_: Exception) {
            15
        }

        fun setConcurrency(value: Int) {
            try {
                CloudStreamApp.setKey(KEY_CONCURRENCY, value.coerceIn(6, 40))
            } catch (_: Exception) {}
        }

        fun liveSportsEnabled(): Boolean = try {
            CloudStreamApp.getKey<Boolean>(KEY_LIVE_SPORTS) ?: true
        } catch (_: Exception) {
            true
        }

        fun liveSportsOnTop(): Boolean = try {
            CloudStreamApp.getKey<Boolean>(KEY_LIVE_SPORTS_TOP) ?: false
        } catch (_: Exception) {
            false
        }

        fun setLiveSports(on: Boolean) {
            try {
                CloudStreamApp.setKey(KEY_LIVE_SPORTS, on)
            } catch (_: Exception) {}
        }

        fun setLiveSportsTop(onTop: Boolean) {
            try {
                CloudStreamApp.setKey(KEY_LIVE_SPORTS_TOP, onTop)
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

    private val tmdbRows = listOf(
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

    // rows load one after another so low end devices don't stall on the burst
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 250L

    // rows are rebuilt on every access so settings changes only need a restart
    override val mainPage: List<MainPageData>
        get() {
            val rows = ArrayList<MainPageData>(48)
            if (liveSportsEnabled() && liveSportsOnTop()) {
                rows.add(MainPageData("Live Sports", LIVE_ROW))
            }
            rows.add(MainPageData(tmdbRows[0].second, tmdbRows[0].first))
            rows.add(MainPageData("Surprise Me", PlayDiscovery.ROW_SURPRISE))
            rows.add(MainPageData(PlayDiscovery.recommendedRowName(), PlayDiscovery.ROW_RECOMMENDED))
            tmdbRows.drop(1).forEach { rows.add(MainPageData(it.second, it.first)) }
            PlayDiscovery.genreRows.forEach { (id, label) ->
                rows.add(MainPageData(label, PlayDiscovery.genreData(id)))
            }
            PlayDiscovery.languageRows.forEach { langRow ->
                rows.add(MainPageData(langRow.label, PlayDiscovery.languageData(langRow)))
            }
            if (liveSportsEnabled() && !liveSportsOnTop()) {
                rows.add(MainPageData("Live Sports", LIVE_ROW))
            }
            return rows
        }

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        // netmirror needs a ~1 min cookie solve on cold start, get it going before the user opens anything
        prefetchScope.launch { NetMirrorSite.prefetch() }
        val data = request.data
        if (data == LIVE_ROW) {
            if (!liveSportsEnabled()) {
                return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
            }
            return LiveSports.homePage(this)
        }
        if (data == PlayDiscovery.ROW_SURPRISE) {
            return PlayDiscovery.surprisePage(this, request.name)
        }
        if (data == PlayDiscovery.ROW_RECOMMENDED) {
            return PlayDiscovery.recommendedPage(this, page, request.name)
        }
        if (data.startsWith(PlayDiscovery.ROW_GENRE)) {
            return PlayDiscovery.genrePage(this, page, data, request.name)
        }
        val parts = data.split("&", limit = 2)
        val path = parts[0]
        val extra = parts.getOrNull(1)?.split("&")?.mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        } ?: emptyList()
        val type = if (data.contains("/movie")) "movie" else "tv"
        val params = (extra + listOf("language" to "en-US", "page" to "$page")).toMap()
        val json = PlayTmdb.get(path, params)
        val results = json?.optJSONArray("results")
        val home = mutableListOf<SearchResponse>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                PlayTmdb.toSearchResponse(this, item, if (path.contains("trending")) "" else type)?.let { home.add(it) }
            }
        }
        val hasNext = (json?.optInt("page", 1) ?: 1) < (json?.optInt("total_pages", 1) ?: 1)
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        prefetchScope.launch { NetMirrorSite.prefetch() }
        val json = PlayTmdb.get(
            "/search/multi",
            mapOf("query" to query, "include_adult" to "false", "page" to "1", "language" to "en-US")
        )
        val results = json?.optJSONArray("results") ?: return null
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            PlayTmdb.toSearchResponse(this, item, "")?.let { items.add(it) }
        }
        return items
    }

    // svg logos are a last resort, the app's image loader cannot render them
    private fun pickTmdbLogo(logos: org.json.JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null
        fun urlOf(logo: org.json.JSONObject) =
            "https://image.tmdb.org/t/p/w500" + logo.optString("file_path")

        var svgFallback: org.json.JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (logo.optString("file_path").isBlank()) continue
            if (logo.optString("iso_639_1").equals("en", ignoreCase = true)) {
                if (!logo.optString("file_path").endsWith(".svg", true)) return urlOf(logo)
                if (svgFallback == null) svgFallback = logo
            }
        }
        svgFallback?.let { return urlOf(it) }

        fun voted(o: org.json.JSONObject) =
            o.optDouble("vote_average", 0.0) > 0.0 && o.optInt("vote_count", 0) > 0

        var best: org.json.JSONObject? = null
        var bestSvg: org.json.JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (!voted(logo) || logo.optString("file_path").isBlank()) continue
            val isSvg = logo.optString("file_path").endsWith(".svg", true)
            val slot = if (isSvg) bestSvg else best
            if (slot == null || logo.optDouble("vote_average", 0.0) > slot.optDouble("vote_average", 0.0) ||
                (logo.optDouble("vote_average", 0.0) == slot.optDouble("vote_average", 0.0) &&
                    logo.optInt("vote_count", 0) > slot.optInt("vote_count", 0))
            ) {
                if (isSvg) bestSvg = logo else best = logo
            }
        }
        best?.let { return urlOf(it) }
        bestSvg?.let { return urlOf(it) }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        prefetchScope.launch { NetMirrorSite.prefetch() }
        // live payloads always carry a slug, they are told apart from tmdb titles first
        val live = try {
            parseJson<LiveLoadData>(url)
        } catch (_: Exception) {
            null
        }
        if (live?.slug != null) {
            return LiveSports.buildLoadResponse(this, live, url)
        }
        val data = try {
            parseJson<PlayTmdbData>(url)
        } catch (_: Exception) {
            return null
        }
        val id = data.id ?: return null
        val type = data.type ?: return null
        val isMovie = type == "movie"

        val detail = PlayTmdb.get(
            "/$type/$id",
            mapOf(
                "language" to "en-US",
                "append_to_response" to "external_ids,credits,videos,recommendations,images"
            )
        ) ?: return null

        val title = detail.optString("title").ifBlank { detail.optString("name") }
        if (title.isBlank()) return null
        // feeds the recommended row, recorded in the background so it never slows this page down
        prefetchScope.launch { PlayHistory.record(detail, type) }
        val orgTitle = detail.optString("original_title").ifBlank { detail.optString("original_name") }
        val releaseDate = detail.optString("release_date").ifBlank { detail.optString("first_air_date") }
        val year = releaseDate.take(4).toIntOrNull()
        val poster = PlayTmdb.imageUrl(detail.optString("poster_path"))
        val backdrop = PlayTmdb.imageUrl(detail.optString("backdrop_path"))
        val overview = detail.optString("overview")
        val runtime = detail.optInt("runtime", 0).takeIf { it > 0 }
        val imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }
        val logo = try {
            pickTmdbLogo(detail.optJSONObject("images")?.optJSONArray("logos"))
        } catch (_: Exception) {
            null
        }

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
                actors.add(ActorData(Actor(name, PlayTmdb.imageUrl(c.optString("profile_path")))))
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
                PlayTmdb.toSearchResponse(this, item, type)?.let { recommendations.add(it) }
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
                this.logoUrl = logo
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
                            val seasonJson = PlayTmdb.get(
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
                                        this.posterUrl = PlayTmdb.imageUrl(ep.optString("still_path"))
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
            this.logoUrl = logo
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
        val live = try {
            parseJson<LiveLoadData>(data)
        } catch (_: Exception) {
            null
        }
        if (live?.slug != null) {
            return LiveSports.resolveLinks(live, callback)
        }

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
            SiteEntry("netmirror") { r, s, c -> NetMirrorSite.invoke(r, s, c) },
            SiteEntry("hicine") { r, s, c -> HiCineSite.invoke(r, s, c) }
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
