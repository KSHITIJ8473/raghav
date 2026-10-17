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
import java.net.URLEncoder
import java.text.Normalizer

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

        // the site search drops these words before matching, scoring mirrors that
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "and", "in", "hindi", "dubbed", "dub", "movie",
            "movies", "season", "episode", "episodes", "watch", "full", "series",
            "show", "cartoon", "anime"
        )
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

    private class NameMatch(val text: String, val words: List<String>, val compact: String, val grams: Set<String>)

    private fun normalizeName(raw: String): String {
        val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{Mn}+"), "")
            .replace("&", " and ")
            .replace("['’`]".toRegex(), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun buildNameMatch(text: String): NameMatch {
        val compact = text.replace(" ", "")
        return NameMatch(text, text.split(" ").filter { it.isNotEmpty() }, compact, trigrams(compact))
    }

    private fun cardNames(card: ToonixCard): List<NameMatch> {
        return (listOf(card.title) + card.aka).mapNotNull { raw ->
            val text = normalizeName(raw)
            if (text.isEmpty()) null else buildNameMatch(text)
        }
    }

    private fun trigrams(compact: String): Set<String> {
        val padded = "  $compact "
        val out = HashSet<String>()
        for (i in 0..padded.length - 3) out.add(padded.substring(i, i + 3))
        return out
    }

    private fun boundedDistance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var row2 = IntArray(0)
        var row1 = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            var rowBest = i
            for (j in 1..b.length) {
                var v = minOf(row1[j] + 1, row[j - 1] + 1, row1[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, row2[j - 2] + 1)
                }
                row[j] = v
                if (v < rowBest) rowBest = v
            }
            if (rowBest > max) return max + 1
            row2 = row1
            row1 = row
        }
        return row1[b.length]
    }

    private fun wordScore(queryWord: String, nameWord: String): Double = when {
        queryWord == nameWord -> 1.0
        nameWord.startsWith(queryWord) -> if (queryWord.length >= 2) 0.9 else 0.6
        else -> {
            val max = when {
                queryWord.length >= 8 -> 2
                queryWord.length >= 4 -> 1
                else -> 0
            }
            if (max == 0) 0.0
            else if (boundedDistance(queryWord, nameWord, max) <= max) 0.75
            else if (nameWord.length > queryWord.length &&
                boundedDistance(queryWord, nameWord.substring(0, queryWord.length), max) <= max
            ) 0.6
            else 0.0
        }
    }

    private fun nameScore(query: NameMatch, name: NameMatch): Double {
        if (name.text.isEmpty()) return 0.0
        if (name.text == query.text || name.compact == query.compact) return 100.0
        val ratio = minOf(1.0, query.compact.length.toDouble() / maxOf(1, name.compact.length))
        if (name.text.startsWith(query.text) || name.compact.startsWith(query.compact)) return 88.0 + 8.0 * ratio
        if (query.compact.length < 2) return 0.0

        var score = 0.0
        if (query.compact.length >= 3 && name.compact.contains(query.compact)) {
            score = 76.0 + 6.0 * ratio
        }
        val meaningful = query.words.filter { it !in STOP_WORDS }.ifEmpty { query.words }
        if (meaningful.isNotEmpty()) {
            var sum = 0.0
            var matched = 0
            for (queryWord in meaningful) {
                var best = 0.0
                for (nameWord in name.words) best = maxOf(best, wordScore(queryWord, nameWord))
                if (best < 0.75 && queryWord.length >= 5 && name.compact.contains(queryWord)) best = 0.8
                sum += best
                if (best > 0) matched++
            }
            val nameWords = name.words.filter { it !in STOP_WORDS }
            val coverage = minOf(1.0, meaningful.size.toDouble() / maxOf(1, nameWords.size))
            score = when {
                matched == meaningful.size -> maxOf(score, 48.0 + sum / meaningful.size * 24.0 + 6.0 * coverage)
                meaningful.size >= 3 && matched.toDouble() / meaningful.size >= 0.66 ->
                    maxOf(score, 25.0 + matched.toDouble() / meaningful.size * 20.0)
                else -> score
            }
        }
        if (query.compact.length >= 4) {
            val overlap = query.grams.count { it in name.grams }.toDouble()
            val jaccard = overlap / maxOf(1, minOf(query.grams.size, name.grams.size))
            if (jaccard >= 0.5) score = maxOf(score, 30.0 + 36.0 * jaccard + 4.0 * ratio)
        }
        return score
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        siteSearch(query)?.let { return it }

        var cards = searchCatalog.load()
        if (cards.isEmpty()) {
            cards = try {
                home().cards
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("Toonix", "home fallback for search failed: ${e.message}")
                emptyList()
            }
        }
        if (cards.isEmpty()) return null

        val needle = normalizeName(query)
        if (needle.isEmpty()) return null
        val queryMatch = buildNameMatch(needle)

        val matches = cards.mapNotNull { card ->
            var best = 0.0
            for (name in cardNames(card)) best = maxOf(best, nameScore(queryMatch, name))
            if (best < 40.0 && card.genres.any { g ->
                    val genre = normalizeName(g)
                    genre == needle || (needle.length >= 3 && genre.startsWith(needle))
                }
            ) {
                best = 40.0
            }
            if (best >= 35.0) best to card else null
        }.sortedWith(
            compareByDescending<Pair<Double, ToonixCard>> { it.first }
                .thenBy { if (it.second.type == "SHOW") 0 else 1 }
                .thenByDescending { it.second.year ?: 0 }
        ).map { it.second }

        return matches.mapNotNull { cardToSearch(it) }
    }

    private suspend fun siteSearch(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            val (html, _) = ToonixApi.fetchPage("${ToonixApi.BASE}/search?q=$encoded", checkTitle = false)
            ToonixApi.parseSearchResults(html)
                .mapNotNull { cardToSearch(it) }
                .ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("Toonix", "site search unavailable: ${e.message}")
            null
        }
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
                        Log.d("Toonix", "season $num page failed for $slug: ${e.message}")
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
                Log.d("Toonix", "episode rail unavailable for $slug: ${e.message}")
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

    // a playlist can load fine while its segments are gone, the player would
    // stall on those so every source gets a one kilobyte segment read first
    private suspend fun streamAlive(playlistUrl: String, referer: String): Boolean {
        val seg = ToonixApi.firstSegmentOf(playlistUrl, referer) ?: return false
        return ToonixApi.probeRange(seg, referer) in 200..399
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
        val player = ToonixApi.parsePlayer(flight)

        val seen = mutableSetOf<String>()
        var emitted = false

        suspend fun emit(label: String, linkUrl: String, type: ExtractorLinkType, height: Int? = null, referer: String? = null) {
            if (!seen.add(linkUrl)) return
            callback(
                newExtractorLink("Toonix", label, linkUrl, type) {
                    quality = qualityFromHeight(height)
                    if (referer != null) this.headers = mapOf("Referer" to referer)
                }
            )
            emitted = true
        }

        // the site numbers its servers by position, legacy is a separate field
        player.servers.forEachIndexed { idx, server ->
            val name = "Server ${idx + 1}"
            if (server.embed || server.direct) {
                val src = server.versions.firstOrNull()?.src?.takeIf { it.isNotBlank() } ?: server.src
                if (src.isBlank()) return@forEachIndexed
                val isHydrax = server.direct || src.contains("/api/hydrax/")
                if (isHydrax) {
                    val quals = ToonixApi.resolveHydrax(src)
                    val probes = coroutineScope {
                        quals.map { q -> async { ToonixApi.probeRange(q.src, "$ToonixApi.BASE/") } }.awaitAll()
                    }
                    quals.forEachIndexed { i, q ->
                        if (q.src.isBlank() || probes[i] !in 200..399) return@forEachIndexed
                        val height = q.label?.removeSuffix("p")?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
                        emit(
                            if (q.label.isNullOrBlank()) name else "$name ${q.label}",
                            q.src,
                            ExtractorLinkType.VIDEO,
                            height,
                            "$ToonixApi.BASE/"
                        )
                    }
                    return@forEachIndexed
                }
                val sources = ToonixApi.resolveEmbedSources(src)
                val probes = coroutineScope {
                    sources.map { s -> async { ToonixApi.probeRange(s.url, "https://player.abyssplayer.com/") } }.awaitAll()
                }
                sources.forEachIndexed { i, s ->
                    if (s.url.isBlank() || s.status == false) return@forEachIndexed
                    if (probes[i] !in 200..399) return@forEachIndexed
                    val height = s.type?.removeSuffix("p")?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
                    emit(
                        if (s.type.isNullOrBlank()) name else "$name ${s.type}",
                        s.url,
                        ExtractorLinkType.VIDEO,
                        height,
                        "https://player.abyssplayer.com/"
                    )
                }
            } else {
                val versions = server.versions.ifEmpty { listOf(ToonixStreamVersion(src = server.src)) }
                for (version in versions) {
                    if (version.src.isBlank()) continue
                    val srcUrl = ToonixApi.absolutize(version.src, url) ?: continue
                    val tag = if (server.versions.size > 1 && !version.label.isNullOrBlank()) "${version.label} " else ""
                    val master = ToonixApi.fetchMaster(srcUrl)
                    when {
                        master.isMaster -> {
                            val probeUrl = master.variants.firstOrNull()?.url ?: continue
                            if (!streamAlive(probeUrl, "${ToonixApi.BASE}/")) continue
                            master.variants.forEach { v ->
                                val q = if (v.height != null) "${v.height}p" else "(auto)"
                                emit("$tag$name $q", v.url, ExtractorLinkType.M3U8, v.height, "${ToonixApi.BASE}/")
                            }
                        }
                        master.isMedia -> {
                            if (!streamAlive(srcUrl, "${ToonixApi.BASE}/")) continue
                            emit("$tag$name (auto)", srcUrl, ExtractorLinkType.M3U8, null, "${ToonixApi.BASE}/")
                        }
                        else -> {}
                    }
                }
            }
        }

        player.legacySrc?.let { legacyRaw ->
            if (legacyRaw.contains("archive.org") || legacyRaw.endsWith(".mp4")) {
                emit("Legacy", legacyRaw, ExtractorLinkType.VIDEO, null, "${ToonixApi.BASE}/")
            } else {
                val master = ToonixApi.fetchMaster(legacyRaw)
                when {
                    master.isMaster -> {
                        val probeUrl = master.variants.firstOrNull()?.url ?: return@let
                        if (!streamAlive(probeUrl, legacyRaw)) return@let
                        master.variants.forEach { v ->
                            val q = if (v.height != null) "${v.height}p" else "(auto)"
                            emit("Legacy $q", v.url, ExtractorLinkType.M3U8, v.height, "${ToonixApi.BASE}/")
                        }
                    }
                    master.isMedia -> {
                        if (!streamAlive(legacyRaw, "${ToonixApi.BASE}/")) return@let
                        emit("Legacy", legacyRaw, ExtractorLinkType.M3U8, null, "${ToonixApi.BASE}/")
                    }
                    else -> {}
                }
            }
        }

        return emitted
    }
}
