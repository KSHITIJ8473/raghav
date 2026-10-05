package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Multimovies"

class MultimoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.garden"
    override var name = "Multimovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "/catalog?type=movie&sort=latest" to "Latest Movies",
        "/catalog?type=tv&sort=latest" to "Latest TV Shows",
        "/catalog?sort=rating" to "Trending",
        "/catalog?genre=bollywood-movies&sort=latest" to "Bollywood",
        "/catalog?genre=hollywood&sort=latest" to "Hollywood",
        "/catalog?genre=south-indian&sort=latest" to "South Indian",
        "/catalog?genre=netflix&sort=latest" to "Netflix",
        "/catalog?genre=disney-hotstar&sort=latest" to "Jio Hotstar",
        "/catalog?genre=anime-series&sort=latest" to "Anime Series",
        "/catalog?genre=anime-movies&sort=latest" to "Anime Movies",
    )

    private val headers = mapOf(
        "User-Agent" to MMNet.UA,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private var validatedDomain: String? = null

    // the firebase list can lag behind migrations, only switch when the reply
    // really looks like the current app shell
    private suspend fun refreshDomain() {
        try {
            val remote = FirebaseDomainHelper.getDomain("multimovies") ?: return
            if (remote == mainUrl || remote == validatedDomain) return
            val ok = try {
                val resp = mmGet("$remote/", headers = headers)
                resp.isSuccessful && resp.text.contains("/assets/js/player.js")
            } catch (e: Exception) {
                Log.d(TAG, "domain check $remote: ${e.message}")
                false
            }
            validatedDomain = remote
            if (ok) mainUrl = remote
        } catch (e: Exception) {
            Log.e(TAG, "refreshDomain: ${e.message}")
            validatedDomain = null
        }
    }

    private fun cardJson(el: org.jsoup.nodes.Element): JSONObject? {
        val raw = el.selectFirst("[data-save-title]")?.attr("data-save-title") ?: return null
        return try {
            JSONObject(raw.replace("&quot;", "\""))
        } catch (e: Exception) {
            Log.d(TAG, "card json: ${e.message}")
            null
        }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (card in this.select("article.poster-card")) {
            val data = cardJson(card) ?: continue
            val url = data.optString("url")
            val title = data.optString("title")
            if (url.isBlank() || title.isBlank() || !seen.add(url)) continue
            val isTv = data.optString("type") == "tv" || url.contains("/series/")
            val tvType = if (isTv) {
                if (url.contains("anime")) TvType.Anime else TvType.TvSeries
            } else {
                if (url.contains("anime")) TvType.Anime else TvType.Movie
            }
            val poster = card.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            } ?: data.optString("poster")
            out.add(
                newMovieSearchResponse(title, url, tvType) {
                    this.posterUrl = poster
                    this.year = data.optInt("year", 0).takeIf { it > 0 }
                }
            )
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        refreshDomain()
        val base = request.data
        val url = if (page <= 1) "$mainUrl$base" else "$mainUrl$base&page=$page"
        return try {
            val doc = mmGet(url, headers = headers).document
            val items = doc.parseCards()
            val hasNext = doc.selectFirst("a[rel=next]") != null
            newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        refreshDomain()
        return try {
            val doc = mmGet(
                "$mainUrl/search?q=${MMNet.urlEncode(query.trim())}",
                headers = headers,
            ).document
            doc.parseCards()
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private data class WatchServer(val id: String, val name: String, val url: String)

    private fun parseWatchConfig(html: String): List<WatchServer> {
        val root = try {
            JSONObject(extractBracedObject(html, "const watchConfig = "))
        } catch (e: Exception) {
            Log.e(TAG, "watchConfig: ${e.message}")
            return emptyList()
        }
        val servers = root.optJSONArray("initialServers") ?: return emptyList()
        val out = mutableListOf<WatchServer>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (url.isBlank()) continue
            out.add(WatchServer(s.optString("id"), s.optString("name"), url))
        }
        return out
    }

    // line ending agnostic json block reader, the site mixes crlf and lf
    private fun extractBracedObject(html: String, marker: String): String {
        val start = html.indexOf(marker)
        if (start < 0) return ""
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until html.length) {
            val ch = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                ch == '{' && !inString -> depth++
                ch == '}' && !inString -> {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
        }
        return ""
    }

    private fun parseMeta(doc: Document): Triple<String, String, String> {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val plot = doc.selectFirst("#cinejoyOverview")?.text()?.trim() ?: ""
        val ldJson = doc.selectFirst("script[type=application/ld+json]")?.data().orEmpty()
        val ld = try {
            if (ldJson.isBlank()) null else JSONObject(ldJson)
        } catch (e: Exception) {
            null
        }
        val resolvedTitle = title.ifBlank { ld?.optString("name").orEmpty() }
        val resolvedPlot = plot.ifBlank { ld?.optString("description").orEmpty() }
        val image = ld?.optString("image")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        return Triple(resolvedTitle, resolvedPlot, image)
    }

    private fun parseGenres(doc: Document): List<String> {
        val fromDetail = doc.selectFirst(".cinejoy-detail-genres")?.text()
            ?.split("•")?.map { it.trim() }?.filter { it.isNotBlank() }
        if (!fromDetail.isNullOrEmpty()) return fromDetail
        val ld = doc.selectFirst("script[type=application/ld+json]")?.data().orEmpty()
        return try {
            val obj = JSONObject(ld)
            val genre = obj.optJSONArray("genre") ?: return emptyList()
            (0 until genre.length()).map { genre.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePills(doc: Document): Triple<Int?, Int?, Double?> {
        val text = doc.select(".cinejoy-meta-pill").eachText().joinToString(" ")
        val year = Regex("\\b((?:19|20)\\d{2})\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("(\\d+)h\\s*(\\d+)m").find(text)?.let {
            it.groupValues[1].toIntOrNull()?.times(60)?.plus(it.groupValues[2].toIntOrNull() ?: 0)
        } ?: Regex("(\\d+)m").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val score = Regex("★\\s*([\\d.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return Triple(year, duration, score)
    }

    private fun parseSeasons(doc: Document): List<Int> {
        // the range select shares the cinejoy-season-select class, only the
        // pill dropdown lists real seasons
        val select = doc.selectFirst(".cinejoy-season-pill-dropdown select") ?: return emptyList()
        return select.select("option").mapNotNull { it.attr("value").toIntOrNull() }.sorted()
    }

    // seasons past 24 episodes come as ep_range pages, the selected range is
    // the one already rendered on the page
    private fun parseEpisodeRanges(doc: Document): List<Int> {
        val select = doc.selectFirst("select.episode-range-select") ?: return emptyList()
        return select.select("option")
            .filterNot { it.hasAttr("selected") }
            .mapNotNull { it.attr("value").toIntOrNull() }
    }

    private fun watchSeason(html: String): Int? = try {
        JSONObject(extractBracedObject(html, "const watchConfig = ")).optInt("season").takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        for (card in doc.select(".cinejoy-ep-card")) {
            val idAttr = card.attr("id")
            val m = Regex("ep-item-(\\d+)-(\\d+)").find(idAttr) ?: continue
            val season = m.groupValues[1].toIntOrNull() ?: continue
            val episode = m.groupValues[2].toIntOrNull() ?: continue
            val href = card.selectFirst("a.cinejoy-ep-thumb-link")?.attr("href") ?: continue
            val name = card.selectFirst(".cinejoy-ep-name")?.text()?.trim().orEmpty()
            val still = card.selectFirst("img")?.attr("src").orEmpty()
            out.add(
                newEpisode(href) {
                    this.name = name.ifBlank { "Episode $episode" }
                    this.season = season
                    this.episode = episode
                    this.posterUrl = still
                }
            )
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun load(url: String): LoadResponse? {
        refreshDomain()
        return try {
            val resp = mmGet(url, headers = headers)
            val doc = resp.document
            val (title, plot, image) = parseMeta(doc)
            if (title.isBlank()) return null
            val genres = parseGenres(doc)
            val (year, duration, score) = parsePills(doc)

            if (url.contains("/series/")) {
                val seriesBase = url.substringBefore('?')
                val seasons = parseSeasons(doc)
                val current = watchSeason(resp.text) ?: seasons.firstOrNull() ?: 1
                val episodes = mutableListOf<Episode>()
                episodes.addAll(parseEpisodes(doc))

                for (range in parseEpisodeRanges(doc)) {
                    try {
                        episodes.addAll(
                            parseEpisodes(mmGet("$seriesBase?season=$current&ep_range=$range", headers = headers).document)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "episodes range $range: ${e.message}")
                    }
                }

                val remaining = seasons.filter { it != current }
                if (remaining.isNotEmpty()) {
                    coroutineScope {
                        remaining.map { season ->
                            async(Dispatchers.IO) {
                                try {
                                    val seasonDoc = mmGet("$seriesBase?season=$season", headers = headers).document
                                    val eps = parseEpisodes(seasonDoc).toMutableList()
                                    for (range in parseEpisodeRanges(seasonDoc)) {
                                        try {
                                            eps.addAll(
                                                parseEpisodes(
                                                    mmGet("$seriesBase?season=$season&ep_range=$range", headers = headers).document
                                                )
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "episodes season $season range $range: ${e.message}")
                                        }
                                    }
                                    eps
                                } catch (e: Exception) {
                                    Log.e(TAG, "episodes season $season: ${e.message}")
                                    emptyList()
                                }
                            }
                        }.forEach { episodes.addAll(it.await()) }
                    }
                }

                val tvType = if (genres.any { it.contains("anime", true) } || url.contains("anime")) {
                    TvType.Anime
                } else {
                    TvType.TvSeries
                }
                if (episodes.isEmpty()) return null
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = image
                    this.backgroundPosterUrl = image
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = score?.let { Score.from10(it) }
                }
            } else {
                newMovieLoadResponse(
                    title,
                    url,
                    if (url.contains("anime")) TvType.Anime else TvType.Movie,
                    url,
                ) {
                    this.posterUrl = image
                    this.backgroundPosterUrl = image
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = score?.let { Score.from10(it) }
                    this.duration = duration
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    private data class TitleIds(val tmdbId: String?, val imdbId: String?)

    private fun extractIds(servers: List<WatchServer>): TitleIds {
        var tmdb: String? = null
        var imdb: String? = null
        for (server in servers) {
            if (tmdb == null) {
                tmdb = Regex("(?:movie\\?id=|watch/movie/|watch/tv/|embed/tmdb/tv\\?id=|/tv/)(\\d+)")
                    .find(server.url)?.groupValues?.get(1)
            }
            if (imdb == null) {
                imdb = Regex("(tt\\d{6,})").find(server.url)?.groupValues?.get(1)
            }
        }
        return TitleIds(tmdb, imdb)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        refreshDomain()
        var any = false
        try {
            val html = mmGet(data, headers = headers).text
            val servers = parseWatchConfig(html)
            if (servers.isEmpty()) return false

            val ids = extractIds(servers)
            val isTv = data.contains("/series/")
            val season = Regex("/season/(\\d+)/episode/").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("/season/\\d+/episode/(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull()
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            val year = parsePills(doc).first?.toString()

            val seenLinks = ConcurrentHashMap.newKeySet<String>()
            val seenSubs = ConcurrentHashMap.newKeySet<String>()
            val linkCb: (ExtractorLink) -> Unit = { link ->
                if (seenLinks.add(link.url)) callback(link)
            }
            val subCb: (SubtitleFile) -> Unit = { sub ->
                if (seenSubs.add(sub.url)) subtitleCallback(sub)
            }

            // the sources are independent services, fetch them side by side
            val jobs = coroutineScope {
                servers.map { server ->
                    async(Dispatchers.IO) {
                        try {
                            resolveServer(server, ids, isTv, season, episode, title, year, subCb, linkCb)
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks ${server.name}: ${e.message}")
                            false
                        }
                    }
                }
            }
            for (job in jobs) any = job.await() || any
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            return any
        }
        return any
    }

    private suspend fun resolveServer(
        server: WatchServer,
        ids: TitleIds,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String,
        year: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val host = MMNet.hostOf(server.url)
        return when {
            host.contains("modiplay.xyz") ->
                MMCineverse.resolve(server.url, server.name, callback)

            host.contains("iqsmartgames.com") ->
                MMIqsmart.resolve(server.url, server.name, callback)

            host.contains("filesforever.link") ->
                MMIqsmart.resolveFilesforever(server.url, server.name, callback)

            host.contains("vidout.pages.dev") -> {
                val tmdb = ids.tmdbId ?: return false
                MMVidout.resolve(tmdb, isTv, season, episode, server.name, subtitleCallback, callback)
            }

            host.contains("vidsync.pro") -> {
                val tmdb = ids.tmdbId
                val url = if (tmdb != null) server.url.replace("{tmdbId}", tmdb) else server.url
                MMVidsync.resolve(url, server.name, callback)
            }

            host.contains("bingr.one") -> {
                val tmdb = ids.tmdbId ?: return false
                MMBingr.resolve(isTv, tmdb, title, year, season, episode, server.name, subtitleCallback, callback)
            }

            host.contains("filmu.in") -> {
                val tmdb = ids.tmdbId ?: return false
                MMFilmu.resolve(isTv, tmdb, season, episode, server.name, subtitleCallback, callback)
            }

            host.contains("vidbolt.xyz") -> {
                val tmdb = ids.tmdbId ?: return false
                MMVidbolt.resolve(isTv, tmdb, ids.imdbId, title, year, season, episode, server.name, callback)
            }

            else -> {
                val html = MMNet.get(server.url, referer = "https://multimovies.garden/")
                if (html != null) {
                    val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
                        .findAll(html)
                        .map { MMNet.deEsc(it.groupValues.first()) }
                        .toSet()
                    for (u in urls) {
                        callback(newExtractorLink(server.name, server.name, u, type = ExtractorLinkType.M3U8))
                    }
                    urls.isNotEmpty()
                } else false
            }
        }
    }
}
