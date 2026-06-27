package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class TheMoviesFlix : MainAPI() {
    override var mainUrl = "https://themoviesflix.xyz"
    override var name = "TheMoviesFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ============================================================
    //  Homepage — uses WordPress category pages
    // ============================================================
    override val mainPage = mainPageOf(
        "" to "Latest Movies & Series",
        "category/movies" to "Movies",
        "category/web-series" to "Web Series",
        "category/hindi-dubbed-movies" to "Hindi Dubbed Movies",
        "category/english-movies" to "English Movies",
        "category/netflix" to "Netflix",
        "category/amazon-prime-video" to "Amazon Prime Video",
        "category/jiohotstar" to "JioHotstar",
        "category/disney" to "Disney+",
        "category/2160p" to "4K UHD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (path.isBlank()) {
            if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        } else {
            if (page > 1) "$mainUrl/$path/page/$page/" else "$mainUrl/$path/"
        }
        val doc = app.get(url, headers = baseHeaders).document
        val items = doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("div.navigation a.nextpostslink, div.navigation li a:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").ifBlank { return null }
        val titleRaw = this.attr("title").ifBlank { return null }
        // Clean up title: "Download Avatar (2009) ..." -> "Avatar (2009)"
        val title = cleanTitle(titleRaw)
        val img = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""
        val quality = getSearchQuality(titleRaw)

        // Detect TV series vs movie from title
        val isSeries = titleRaw.contains("Season", true) || titleRaw.contains("Series", true) ||
                titleRaw.contains("S01", true) || titleRaw.contains("S1", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = img
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img
                this.quality = quality
            }
        }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        // Remove "Download " prefix
        t = t.replace(Regex("^Download\\s+", RegexOption.IGNORE_CASE), "")
        // Remove quality/size info after the title
        t = t.substringBefore(" 480p").substringBefore(" 720p").substringBefore(" 1080p")
        t = t.substringBefore(" {Hindi").substringBefore(" {English")
        t = t.substringBefore(" (Hindi").substringBefore(" (English")
        t = t.substringBefore(" Hindi Dubbed").substringBefore(" Dual Audio")
        t = t.substringBefore(" [480p").substringBefore(" [720p").substringBefore(" [1080p")
        t = t.substringBefore(" Web Dl").substringBefore(" WEB-DL").substringBefore(" BluRay")
        t = t.substringBefore(" Full Movie").substringBefore(" Complete")
        return t.trim().trimEnd('(', '-', ':')
    }

    private fun getSearchQuality(text: String): SearchQuality? {
        return when {
            text.contains("2160p", true) || text.contains("4K", true) || text.contains("UHD", true) -> SearchQuality.FourK
            text.contains("1080p", true) || text.contains("FullHD", true) -> SearchQuality.HD
            text.contains("720p", true) -> SearchQuality.SD
            text.contains("480p", true) -> SearchQuality.SD
            else -> null
        }
    }

    // ============================================================
    //  Search — uses WordPress ?s= query
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = baseHeaders).document
        return doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { it.toSearchResult() }
    }

    // ============================================================
    //  Load — parse movie/series detail page
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders).document
        val entry = doc.selectFirst("div.entry-content") ?: return null

        // Extract title from h2.mfx-main-title or page title
        val titleRaw = doc.selectFirst("h2.mfx-main-title")?.text()
            ?: doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: return null
        val title = cleanTitle(titleRaw)

        // Extract metadata
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = entry.selectFirst("div.mfx-plot-box")?.text()?.trim()
        val year = extractYear(entry) ?: extractYearFromTitle(titleRaw)

        // Extract genres, cast, runtime from the info box
        val genres = extractListFromInfo(entry, "Genres")
        val cast = extractListFromInfo(entry, "Cast").map { ActorData(Actor(it)) }
        val runtime = extractFieldFromInfo(entry, "Runtime")?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val languages = extractListFromInfo(entry, "Language")

        // Extract IMDb rating and ID
        val imdbLink = entry.selectFirst("div.mfx-imdb a[href*=imdb]")?.attr("href") ?: ""
        val imdbId = Regex("""title/(tt\d+)""").find(imdbLink)?.groupValues?.get(1)
        val ratingText = entry.selectFirst("div.mfx-imdb a")?.text() ?: ""
        val rating = Regex("""([\d.]+)/10""").find(ratingText)?.groupValues?.get(1)?.toFloatOrNull()

        // Extract trailer
        val trailerId = entry.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")
        val trailer = trailerId?.let { "https://www.youtube.com/watch?v=$it" }

        // Determine if it's a movie or TV series
        val isSeries = titleRaw.contains("Season", true) || titleRaw.contains("Series", true) ||
                titleRaw.contains("S01", true) || titleRaw.contains("S1 ", true) ||
                doc.select("div.mfx-download-group h3:contains(Episode)").isNotEmpty()

        // Extract all download links grouped by quality
        val downloadGroups = extractDownloadGroups(entry, isSeries)

        if (isSeries) {
            // For TV series, each download group is an episode quality option
            // We create one "episode" per quality, labeled with the quality info
            val episodes = downloadGroups.mapIndexed { index, group ->
                newEpisode(group.redirectUrl) {
                    this.name = group.label
                    this.episode = index + 1
                    this.season = extractSeasonNumber(group.label) ?: 1
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = rating?.let { Score.from10(it) }
                this.actors = cast
                this.duration = runtime
                if (imdbId != null) addImdbId(imdbId)
                if (trailer != null) addTrailer(trailer)
            }
        } else {
            // For movies, store all download URLs as newline-separated string
            val dataStr = downloadGroups.joinToString("\n") { it.redirectUrl }
            return newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = rating?.let { Score.from10(it) }
                this.actors = cast
                this.duration = runtime
                if (imdbId != null) addImdbId(imdbId)
                if (trailer != null) addTrailer(trailer)
            }
        }
    }

    private data class DownloadGroup(
        val label: String,
        val redirectUrl: String
    )

    private fun extractDownloadGroups(entry: Element, isSeries: Boolean): List<DownloadGroup> {
        val groups = mutableListOf<DownloadGroup>()
        val divs = entry.select("div.mfx-download-group")
        for (div in divs) {
            val label = div.selectFirst("h3")?.text()?.trim() ?: continue
            val link = div.selectFirst("a[href]")?.attr("href") ?: continue
            if (link.isNotBlank() && link != "#") {
                groups.add(DownloadGroup(label, link))
            }
        }
        return groups
    }

    private fun extractYear(entry: Element): Int? {
        val yearText = entry.selectFirst("li:contains(Release Year)")?.text()
            ?: entry.selectFirst("li:contains(Year)")?.text()
        return yearText?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractYearFromTitle(title: String): Int? {
        return Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSeasonNumber(text: String): Int? {
        return Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractFieldFromInfo(entry: Element, fieldName: String): String? {
        val li = entry.selectFirst("li:has(strong:contains($fieldName))")
            ?: entry.selectFirst("li:contains($fieldName)")
        return li?.text()?.substringAfter(":")?.trim()
    }

    private fun extractListFromInfo(entry: Element, fieldName: String): List<String> {
        val text = extractFieldFromInfo(entry, fieldName) ?: return emptyList()
        return text.split(",", "&", "/").map { it.trim() }.filter { it.isNotBlank() && it != "N/A" }
    }

    // ============================================================
    //  Load Links — resolve redirect chain to direct video URLs
    // ============================================================
    //
    //  Flow:
    //    1. Movie: data string contains newline-separated redirect URLs
    //       TV: data string is a single redirect URL (one per episode)
    //    2. Each redirect URL (mobilejsr.rest or nexdrive.fit) is a WordPress
    //       page that contains the actual download links
    //    3. Fetch the redirect page, find links to fastdl.zip/embed.php?download=...
    //    4. Fetch the fastdl.zip page, extract `var reurl = "...link=<GOOGLE_URL>"`
    //    5. The Google URL is a direct playable MP4/MKV — emit as ExtractorLink
    //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val redirectUrls = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (redirectUrls.isEmpty()) return false

        var foundAny = false

        for (redirectUrl in redirectUrls) {
            try {
                val links = resolveRedirectPage(redirectUrl)
                for (link in links) {
                    try {
                        // Try fastdl.zip first (most reliable - direct Google Drive)
                        if (link.contains("fastdl")) {
                            val directUrl = resolveFastDl(link)
                            if (directUrl != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "TheMoviesFlix",
                                        name = "G-Direct [FastDL]",
                                        url = directUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundAny = true
                            }
                        }
                        // Try filebee/filepress (redirects to filepress.baby)
                        else if (link.contains("filebee") || link.contains("filepress")) {
                            val directUrl = resolveFilePress(link)
                            if (directUrl != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "TheMoviesFlix",
                                        name = "G-Direct [FilePress]",
                                        url = directUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundAny = true
                            }
                        }
                        // For all other hosts, try CloudStream's built-in extractors
                        // (handles vcloud.zip, gofile, 1fichier, megaup, pixeldrain, etc.)
                        else {
                            val loaded = loadExtractor(link, "https://nexdrive.fit/", subtitleCallback, callback)
                            if (loaded) foundAny = true
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }

        return foundAny
    }

    // ============================================================
    //  Helper: Fetch the redirect page (mobilejsr.rest / nexdrive.fit)
    //  and extract the actual download links
    // ============================================================
    //
    //  IMPORTANT: The site uses TWO redirect domains:
    //    - nexdrive.fit   → returns 200, works fine
    //    - mobilejsr.rest → returns 403 Cloudflare challenge, BLOCKED
    //  Both serve the SAME content for the same /genxfm.../ path.
    //  Fix: rewrite mobilejsr.rest URLs to nexdrive.fit before fetching.
    //
    private suspend fun resolveRedirectPage(url: String): List<String> {
        return try {
            // Rewrite mobilejsr.rest → nexdrive.fit (same backend, nexdrive isn't CF-blocked)
            val fixedUrl = url.replace("mobilejsr.rest", "nexdrive.fit")
            val doc = app.get(fixedUrl, headers = baseHeaders + ("Referer" to "$mainUrl/")).document
            val article = doc.selectFirst("article") ?: doc.selectFirst("div.entry-content") ?: return emptyList()
            val links = mutableSetOf<String>()

            // Find all anchor links
            for (a in article.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.isBlank() || href.startsWith("#")) continue
                if (href.contains("nexdrive") || href.contains("mobilejsr") || href.contains("moviesflix")) continue
                // Collect download host links — match ALL known file hosts
                if (href.contains("fastdl") || href.contains("filebee") || href.contains("filepress") ||
                    href.contains("vcloud") || href.contains("mcloud") || href.contains("gdtot") ||
                    href.contains("gdflix") || href.contains("gofile") || href.contains("hubcloud") ||
                    href.contains("hubdrive") || href.contains("drive.google") || href.contains("gdrive") ||
                    href.contains("1fichier") || href.contains("megaup") || href.contains("mega.nz") ||
                    href.contains("katfile") || href.contains("uploadhaven") || href.contains("pixeldrain") ||
                    href.contains("filesdm") || href.contains("filedm") || href.contains("dropbox")) {
                    links.add(href)
                }
            }
            links.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================================================
    //  Helper: Resolve fastdl.zip embed page to direct Google Drive URL
    // ============================================================
    //
    //  The nexdrive page links to either:
    //    - fastdl.zip/embed.php?download=...  (works, returns reurl)
    //    - fastdl.zip/embed?download=...      (returns "File Deleted" error)
    //  Fix: normalize /embed? → /embed.php? before fetching.
    //
    private suspend fun resolveFastDl(url: String): String? {
        return try {
            // Normalize: ensure URL uses /embed.php? not /embed?
            val fixedUrl = url.replace("/embed?", "/embed.php?")
            val html = app.get(fixedUrl, headers = baseHeaders + ("Referer" to "https://nexdrive.fit/")).text
            // Pattern: var reurl = "https://fastdl.zip/dl.php?link=<GOOGLE_URL>"
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return null
            // Extract the Google Drive URL from the link= parameter
            val googleUrl = Regex("""link=(https?://[^&"]+)""").find(reurl)?.groupValues?.get(1)
                ?: return null
            URLDecoder.decode(googleUrl, "UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    //  Helper: Resolve filebee.xyz / filepress.baby redirect chain
    // ============================================================
    private suspend fun resolveFilePress(url: String): String? {
        return try {
            // filebee.xyz redirects to filepress.baby which is Cloudflare-protected
            // Try to follow the redirect and extract the direct link
            val html = app.get(url, headers = baseHeaders + ("Referer" to "$mainUrl/")).text

            // Look for direct download links in the page
            val directLink = Regex("""(https?://[^"'\s<>]+\.mkv[^"'\s<>]*)""").find(html)?.groupValues?.get(1)
                ?: Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""").find(html)?.groupValues?.get(1)
                ?: Regex("""href="(https?://[^"]*(?:drive\.google|googleapis|googleusercontent)[^"]*)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""href="(https?://[^"]*download[^"]*)"""").find(html)?.groupValues?.get(1)

            directLink
        } catch (_: Exception) {
            null
        }
    }
}
