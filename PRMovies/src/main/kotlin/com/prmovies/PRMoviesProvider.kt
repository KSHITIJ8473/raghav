package com.prmovies

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PRMoviesProvider : MainAPI() {
    override var mainUrl = "https://prmovies.recipes"
    override var name = "PRMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val cfKiller = CloudflareKiller()

    private val fallbackDomains = listOf(
        "https://prmovies.recipes",
        "https://prmovies.com",
        "https://prmovies.top",
        "https://prmovies.org"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "genre/tv-shows" to "TV Shows & Web Series",
        "genre/bollywood-movies" to "Bollywood Movies",
        "genre/hollywood-movies" to "Hollywood Movies",
        "genre/dual-audio-movies" to "Dual Audio",
        "director/ullu-originals" to "Ullu Originals",
        "genre/action" to "Action",
        "genre/comedy" to "Comedy"
    )

    private companion object {
        private val YEAR_REGEX = Regex("""\(?(\d{4})\)?""")
        private val RATING_REGEX = Regex("""(?:IMDb|Rating):\s*([\d.]+)""", RegexOption.IGNORE_CASE)
        private val DURATION_REGEX = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val EPISODE_NUM_REGEX = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val SEASON_NUM_REGEX = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
    }

    private fun getSearchQuality(text: String?): SearchQuality? {
        if (text.isNullOrBlank()) return null
        return when {
            text.contains("4K", true) || text.contains("2160p", true) -> SearchQuality.FourK
            text.contains("1080p", true) || text.contains("FHD", true) || text.contains("HD", true) -> SearchQuality.HD
            text.contains("720p", true) || text.contains("SD", true) || text.contains("480p", true) -> SearchQuality.SD
            text.contains("CAM", true) || text.contains("HDTS", true) -> SearchQuality.Cam
            else -> null
        }
    }

    private suspend fun fetchDoc(url: String): Document {
        try {
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = 15_000L)
            if (res.isSuccessful && res.text.isNotBlank()) {
                return res.document
            }
        } catch (_: Exception) {
            // Try fallback domains if mainUrl failed
            for (domain in fallbackDomains) {
                if (url.startsWith(mainUrl) && domain != mainUrl) {
                    val fallbackUrl = url.replace(mainUrl, domain)
                    try {
                        val res = app.get(fallbackUrl, headers = headers, interceptor = cfKiller, timeout = 15_000L)
                        if (res.isSuccessful && res.text.isNotBlank()) {
                            mainUrl = domain
                            return res.document
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return app.get(url, headers = headers, interceptor = cfKiller, timeout = 15_000L).document
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.ml-mask, a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = selectFirst("img")
        val rawTitle = a.attr("title").ifBlank { a.attr("oldtitle") }.ifBlank { img?.attr("alt") ?: text() }
        val title = rawTitle.replace(YEAR_REGEX, "").replace("Watch Online", "", ignoreCase = true).trim()

        val posterUrl = img?.let {
            it.attr("data-original").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
        }?.let { fixUrlNull(it) }

        val quality = selectFirst(".mli-quality")?.text()?.trim()
        val isTvSeries = href.contains("season", ignoreCase = true) || href.contains("episode", ignoreCase = true) || title.contains("Season", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getSearchQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getSearchQuality(quality)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trim('/')
        val url = when {
            base.isBlank() -> if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
            base.contains("?") -> if (page <= 1) "$mainUrl/$base" else "$mainUrl/page/$page/$base"
            else -> if (page <= 1) "$mainUrl/$base/" else "$mainUrl/$base/page/$page/"
        }

        val doc = fetchDoc(url)
        val items = doc.select(".ml-item, article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = doc.selectFirst("a.next, .pagination a[href*='/page/${page + 1}/']") != null

        return newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "+")
        val url = "$mainUrl/?s=$cleanQuery"
        val doc = fetchDoc(url)
        return doc.select(".ml-item, .result-item, article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)

        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst(".mvic-desc h3, .entry-title, h1")?.text()
            ?: doc.title()

        val title = rawTitle.replace(YEAR_REGEX, "").replace("Watch Online", "", ignoreCase = true).trim()

        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".mvic-thumb img, img.thumb, .poster img")?.let {
                it.attr("data-original").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
            }
        val cleanPosterUrl = if (posterUrl != null) fixUrl(posterUrl) else null

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst(".mvic-desc p, .desc-content, .entry-content")?.text()?.trim()

        var year: Int? = YEAR_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        var rating: Float? = null
        var duration: Int? = null
        val genres = mutableListOf<String>()

        doc.select(".mvic-info p").forEach { p ->
            val text = p.text()
            if (year == null && text.contains("Release:", ignoreCase = true)) {
                year = YEAR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (text.contains("IMDb:", ignoreCase = true) || text.contains("Rating:", ignoreCase = true)) {
                rating = RATING_REGEX.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            }
            if (text.contains("Duration:", ignoreCase = true)) {
                duration = DURATION_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (text.contains("Genre:", ignoreCase = true)) {
                p.select("a").forEach { a ->
                    genres.add(a.text().trim())
                }
            }
        }

        val isTvSeries = url.contains("season", ignoreCase = true) ||
                url.contains("episode", ignoreCase = true) ||
                title.contains("Season", ignoreCase = true) ||
                genres.any { it.contains("TV", ignoreCase = true) || it.contains("Series", ignoreCase = true) }

        val embedUrls = mutableListOf<String>()

        // 1. Check tab containers (tab1, tab2, etc.)
        doc.select("div[id^='tab'], #content-embed, .movieplay").forEach { container ->
            container.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-lazy-src") }.ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && src != "about:blank") {
                    embedUrls.add(fixUrl(src))
                }
            }
        }

        // 2. Check noscript iframes
        doc.select("noscript iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src != "about:blank") {
                embedUrls.add(fixUrl(src))
            }
        }

        // 3. Check server links / les-content links / a.lnk-lnk download links
        doc.select(".les-content a, .server-item a, a.lnk-lnk, a.lnk-dl, a[data-link]").forEach { a ->
            val href = a.attr("data-link").ifBlank { a.attr("href") }
            if (href.isNotBlank() && (href.startsWith("http") || href.contains("embed"))) {
                embedUrls.add(fixUrl(href))
            }
        }

        val distinctEmbeds = embedUrls.distinct()

        if (isTvSeries) {
            val seasonNum = SEASON_NUM_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodeNum = EPISODE_NUM_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val episodes = listOf(
                newEpisode(distinctEmbeds.joinToString(",")) {
                    this.name = title
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            )

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = cleanPosterUrl
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                this.duration = duration
                this.tags = genres
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, distinctEmbeds.joinToString(",")) {
                this.posterUrl = cleanPosterUrl
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                this.duration = duration
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls = data.split(",").map { it.trim() }.filter { it.isNotBlank() }
        var found = false

        for (embedUrl in urls) {
            var loaded = loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            if (!loaded) {
                try {
                    val res = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/"), interceptor = cfKiller)
                    val text = res.text
                    val unpacked = JsUnpacker(text).unpack() ?: text
                    val m3u8Url = M3U8_REGEX.find(unpacked)?.value ?: M3U8_REGEX.find(text)?.value
                    if (m3u8Url != null) {
                        M3u8Helper.generateM3u8(
                            name,
                            m3u8Url,
                            embedUrl,
                            headers = mapOf("Referer" to embedUrl)
                        ).forEach(callback)
                        loaded = true
                    }
                } catch (_: Exception) {
                }
            }
            if (loaded) {
                found = true
            }
        }

        return found
    }
}
