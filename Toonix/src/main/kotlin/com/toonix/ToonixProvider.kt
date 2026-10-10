package com.toonix

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import com.raghav.donation.DonationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Toonix : MainAPI() {
    override var mainUrl = ToonixApi.BASE
    override var name = "Toonix"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie, TvType.TvSeries)
    override var lang = "hi"

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "shows" to "Popular Shows",
        "allshows" to "All Shows",
        "movies" to "Popular Movies",
        "allmovies" to "All Movies"
    )

    private class HomeData(
        val sections: Map<String, List<ToonixCard>>,
        val cards: List<ToonixCard>
    )

    private class CatalogCache(val path: String, private val ttlMillis: Long) {
        @Volatile
        private var entry: Pair<Long, List<ToonixCard>>? = null

        private fun fresh(): List<ToonixCard>? =
            entry?.takeIf { System.currentTimeMillis() - it.first < ttlMillis }?.second

        suspend fun load(): List<ToonixCard> {
            fresh()?.let { return it }
            return fetchLock.withLock {
                fresh()?.let { return@withLock it }
                try {
                    val (_, flight) = ToonixApi.fetchPage(ToonixApi.BASE + path, checkTitle = false)
                    val cards = ToonixApi.parseCards(flight)
                    if (cards.isNotEmpty()) {
                        entry = System.currentTimeMillis() to cards
                    }
                    cards
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d("Toonix", "catalog $path unavailable: ${e.message}")
                    emptyList()
                }
            }
        }
    }

    @Volatile
    private var homeCache: Pair<Long, HomeData>? = null

    private val showsCatalog = CatalogCache("/tv-shows", 15 * 60_000L)
    private val moviesCatalog = CatalogCache("/movies", 15 * 60_000L)
    private val searchCatalog = CatalogCache("/search", 15 * 60_000L)

    companion object {
        private val fetchLock = Mutex()
    }

    private fun cachedHome(): HomeData? =
        homeCache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.second

    private suspend fun home(): HomeData {
        cachedHome()?.let { return it }
        return fetchLock.withLock {
            cachedHome()?.let { return@withLock it }
            val (_, flight) = ToonixApi.fetchPage(ToonixApi.BASE + "/", checkTitle = false)
            val data = HomeData(
                sections = mapOf(
                    "Trending Now" to ToonixApi.sectionCards(flight, "Trending Now"),
                    "Popular Shows" to ToonixApi.sectionCards(flight, "Popular Shows"),
                    "Popular Movies" to ToonixApi.sectionCards(flight, "Popular Movies")
                ),
                cards = ToonixApi.parseCards(flight)
            )
            homeCache = System.currentTimeMillis() to data
            data
        }
    }

    private fun tvTypeOf(card: ToonixCard): TvType = when {
        card.type == "MOVIE" -> TvType.Movie
        card.category == "ANIME" -> TvType.Anime
        else -> TvType.Cartoon
    }

    private fun cardToSearch(card: ToonixCard): SearchResponse? {
        if (card.title.isBlank() || card.href.isBlank()) return null
        val url = ToonixApi.BASE + card.href
        return if (card.type == "MOVIE") {
            newMovieSearchResponse(card.title, url, TvType.Movie) {
                posterUrl = card.poster
                year = card.year
            }
        } else {
            newTvSeriesSearchResponse(card.title, url, tvTypeOf(card)) {
                posterUrl = card.poster
                year = card.year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val home = try {
            home()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not load the homepage: ${e.message}")
        }
        val cards = when (request.data) {
            "trending" -> home.sections["Trending Now"].orEmpty().ifEmpty { home.cards.take(20) }
            "shows" -> home.sections["Popular Shows"].orEmpty().ifEmpty { home.cards.filter { it.type == "SHOW" } }
            "allshows" -> showsCatalog.load().ifEmpty { home.cards.filter { it.type == "SHOW" } }
            "movies" -> home.sections["Popular Movies"].orEmpty().ifEmpty { home.cards.filter { it.type == "MOVIE" } }
            else -> moviesCatalog.load().ifEmpty { home.cards.filter { it.type == "MOVIE" } }
        }
        return newHomePageResponse(request.name, cards.mapNotNull { cardToSearch(it) }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        var cards = searchCatalog.load()
        if (cards.isEmpty()) {
            cards = try {
                home().cards
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (cards.isEmpty()) return null
        val q = query.trim().lowercase()
        val matches = cards.filter { it.title.lowercase().contains(q) }
        val chosen = matches.ifEmpty { cards }
        return chosen.mapNotNull { cardToSearch(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(ToonixApi.BASE)
        return when {
            path.startsWith("/show/") -> loadShow(url)
            path.startsWith("/title/") -> loadMovie(url)
            path.startsWith("/watch/") -> loadMovie(url.replaceFirst("/watch/", "/title/"), watchUrl = url)
            else -> null
        }
    }

    private fun showEpisodesFromRail(
        slug: String,
        seasons: List<ToonixRailSeason>
    ): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (season in seasons) {
            val sNum = season.number ?: continue
            for ((idx, ep) in season.episodes.withIndex()) {
                val eNum = ep.number ?: (idx + 1)
                val name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $eNum"
                episodes.add(
                    newEpisode("${ToonixApi.BASE}/show/$slug/$sNum/$eNum") {
                        this.name = name
                        this.season = sNum
                        this.episode = eNum
                        this.posterUrl = ep.image
                    }
                )
            }
        }
        return episodes
    }

    private suspend fun showEpisodesFromSeasonPages(
        slug: String,
        seasonHrefs: List<Pair<Int, String>>
    ): List<com.lagradost.cloudstream3.Episode> {
        val seasons = coroutineScope {
            seasonHrefs.map { (num, _) ->
                async {
                    try {
                        val (_, flight) = ToonixApi.fetchPage("${ToonixApi.BASE}/show/$slug/$num")
                        val epRegex = Regex(""""href":"/show/${Regex.escape(slug)}/(\d+)/(\d+)".{0,1200}?"alt":"([^"]+)"""")
                        val eps = epRegex.findAll(flight).mapNotNull { m ->
                            val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                            val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                            Triple(s, e, m.groupValues[3])
                        }.toList()
                        num to eps
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        num to emptyList<Triple<Int, Int, String>>()
                    }
                }
            }.awaitAll()
        }
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val seen = mutableSetOf<Pair<Int, Int>>()
        for ((_, eps) in seasons) {
            for ((s, e, title) in eps) {
                if (!seen.add(s to e)) continue
                episodes.add(
                    newEpisode("${ToonixApi.BASE}/show/$slug/$s/$e") {
                        this.name = title
                        this.season = s
                        this.episode = e
                    }
                )
            }
        }
        return episodes
    }

    private suspend fun loadShow(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val (html, flight) = ToonixApi.fetchPage(url)
        val info = ToonixApi.parseShow(html, flight, slug)

        var episodes: List<com.lagradost.cloudstream3.Episode> = emptyList()
        if (info.episodeRefs.isNotEmpty()) {
            val (s0, e0) = info.episodeRefs.first()
            try {
                val (_, epFlight) = ToonixApi.fetchPage("$url/$s0/$e0")
                val rail = ToonixApi.parseSeasonsRail(epFlight)
                if (rail.isNotEmpty() && rail.any { it.episodes.isNotEmpty() }) {
                    episodes = showEpisodesFromRail(slug, rail)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // season pages below still cover this
            }
        }
        if (episodes.isEmpty() && info.seasonHrefs.isNotEmpty()) {
            episodes = showEpisodesFromSeasonPages(slug, info.seasonHrefs)
        }
        if (episodes.isEmpty() && info.episodeRefs.isNotEmpty()) {
            episodes = info.episodeRefs.map { (s, e) ->
                newEpisode("$url/$s/$e") {
                    this.name = "Episode $e"
                    this.season = s
                    this.episode = e
                }
            }
        }
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("No episodes found for this show")
        }

        val type = if (info.genres.any { it.equals("Anime", true) }) TvType.Anime else TvType.Cartoon
        return newTvSeriesLoadResponse(info.title, url, type, episodes) {
            posterUrl = info.poster
            year = info.year
            plot = info.synopsis
            tags = info.genres
        }
    }

    private suspend fun loadMovie(url: String, watchUrl: String? = null): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val (html, flight) = ToonixApi.fetchPage(url)
        val info = ToonixApi.parseMovie(html, flight, slug)
        val data = watchUrl ?: "${ToonixApi.BASE}/watch/$slug"
        return newMovieLoadResponse(info.title, url, TvType.Movie, data) {
            posterUrl = info.poster
            year = info.year
            plot = info.synopsis
            duration = info.runtimeMinutes
            tags = info.genres
        }
    }

    private fun qualityFromHeight(height: Int?): Int {
        val h = height ?: return Qualities.Unknown.value
        return when {
            h >= 2160 -> Qualities.P2160.value
            h >= 1440 -> Qualities.P1440.value
            h >= 1080 -> Qualities.P1080.value
            h >= 720 -> Qualities.P720.value
            h >= 480 -> Qualities.P480.value
            h >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (data.startsWith("http")) data else ToonixApi.BASE + data
        val (_, flight) = try {
            ToonixApi.fetchPage(url)
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not open the episode page: ${e.message}")
        }
        val (fastRaw, legacyRaw) = ToonixApi.parseStreamSrcs(flight)

        val seen = mutableSetOf<String>()
        var emitted = false
        var busyFallback: Pair<String, String>? = null

        suspend fun emit(label: String, linkUrl: String, type: ExtractorLinkType, height: Int? = null) {
            if (!seen.add(linkUrl)) return
            callback(
                newExtractorLink("Toonix", label, linkUrl, type) {
                    quality = qualityFromHeight(height)
                    this.headers = mapOf("Referer" to "${ToonixApi.BASE}/")
                }
            )
            emitted = true
        }

        if (fastRaw != null) {
            val fastUrl = ToonixApi.absolutize(fastRaw, url)
            val master = ToonixApi.fetchMaster(fastUrl)
            when {
                master.isMaster -> master.variants.forEach { v ->
                    val label = if (v.height != null) "Fast ${v.height}p" else "Fast (auto)"
                    emit(label, v.url, ExtractorLinkType.M3U8, v.height)
                }
                master.isMedia -> emit("Fast (auto)", fastUrl, ExtractorLinkType.M3U8)
                master.isBusy -> busyFallback = "Fast (busy, retry later)" to fastUrl
            }
        }

        if (legacyRaw != null) {
            if (legacyRaw.contains("archive.org") || legacyRaw.endsWith(".mp4")) {
                emit("Archive", legacyRaw, ExtractorLinkType.VIDEO)
            } else {
                val master = ToonixApi.fetchMaster(legacyRaw)
                when {
                    master.isMaster -> master.variants.forEach { v ->
                        val label = if (v.height != null) "Backup ${v.height}p" else "Backup (auto)"
                        emit(label, v.url, ExtractorLinkType.M3U8, v.height)
                    }
                    master.isMedia -> emit("Backup (auto)", legacyRaw, ExtractorLinkType.M3U8)
                }
            }
        }

        if (!emitted && busyFallback != null) {
            emit(busyFallback.first, busyFallback.second, ExtractorLinkType.M3U8)
        }

        return emitted
    }
}
