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

        // Detect TV series vs movie from title — check for Season/Series/Web Series/TV Show/S\d+
        val isSeries = titleRaw.contains("Season", true) ||
                titleRaw.contains("Series", true) ||
                titleRaw.contains("Web Series", true) ||
                titleRaw.contains("TV Show", true) ||
                Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(titleRaw)

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

        // Also get the full page title (contains more season/series info)
        val pageTitle = doc.selectFirst("title")?.text() ?: ""

        // Extract metadata
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = entry.selectFirst("div.mfx-plot-box")?.text()?.trim()
        val year = extractYear(entry) ?: extractYearFromTitle(titleRaw)

        // Extract genres, cast, runtime from the info box
        val genres = extractListFromInfo(entry, "Genres")
        val cast = extractListFromInfo(entry, "Cast").map { ActorData(Actor(it)) }
        val runtime = extractFieldFromInfo(entry, "Runtime")?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // Extract IMDb rating and ID
        val imdbLink = entry.selectFirst("div.mfx-imdb a[href*=imdb]")?.attr("href") ?: ""
        val imdbId = Regex("""title/(tt\d+)""").find(imdbLink)?.groupValues?.get(1)
        val ratingText = entry.selectFirst("div.mfx-imdb a")?.text() ?: ""
        val rating = Regex("""([\d.]+)/10""").find(ratingText)?.groupValues?.get(1)?.toFloatOrNull()

        // Extract trailer
        val trailerId = entry.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")
        val trailer = trailerId?.let { "https://www.youtube.com/watch?v=$it" }

        // Extract all download links grouped by quality
        val downloadGroups = extractDownloadGroups(entry)

        // Determine if it's a movie or TV series
        // Check multiple sources: titleRaw, pageTitle, URL, and download group labels
        val allTextToCheck = titleRaw + " " + pageTitle + " " + url + " " +
            downloadGroups.joinToString(" ") { it.label }
        val isSeries = allTextToCheck.contains("Season", true) ||
                allTextToCheck.contains("Series", true) ||
                allTextToCheck.contains("Web Series", true) ||
                allTextToCheck.contains("TV Show", true) ||
                allTextToCheck.contains("Episode", true) ||
                Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(allTextToCheck)

        if (isSeries) {
            // For TV series, we need to fetch each nexdrive redirect page to find
            // individual episodes. Each download group is a quality for a season.
            // The nexdrive page has h4 headings "-:Episodes: N:-" followed by <p>
            // with download links for that episode.
            //
            // Strategy: fetch the FIRST quality group's nexdrive page to discover
            // all episodes, then create one Episode per episode found. Store the
            // nexdrive URL for each quality so loadLinks can fetch the right
            // episode from the right quality page.
            //
            // Data string format for TV episodes:
            //   "nexdrive_url_1|nexdrive_url_2|...|nexdrive_url_N|season_num|episode_num"
            // where each nexdrive_url corresponds to a quality (480p, 720p, 1080p).
            // loadLinks will fetch each nexdrive page, find the episode by number,
            // and extract the fastdl link.

            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            val seasonGroups = mutableMapOf<Int, MutableList<DownloadGroup>>()

            // Group download groups by season
            for (group in downloadGroups) {
                val seasonNum = extractSeasonNumber(group.label) ?: 1
                seasonGroups.getOrPut(seasonNum) { mutableListOf() }.add(group)
            }

            // For each season, fetch the first quality's nexdrive page to discover episodes
            for ((seasonNum, groups) in seasonGroups) {
                if (groups.isEmpty()) continue

                // Use the first quality group to discover episodes
                val firstGroup = groups.first()
                val allNexdriveUrls = groups.joinToString("|") { it.redirectUrl }

                // Fetch the nexdrive page to find episode count
                val episodeLinks = resolveNexdriveEpisodes(firstGroup.redirectUrl)

                if (episodeLinks.isEmpty()) {
                    // No episodes found — treat as single "complete season" download
                    val dataStr = "$allNexdriveUrls|$seasonNum|1"
                    episodes.add(newEpisode(dataStr) {
                        this.name = firstGroup.label
                        this.episode = 1
                        this.season = seasonNum
                    })
                } else {
                    // Create one episode per discovered episode
                    for ((epNum, _) in episodeLinks) {
                        val dataStr = "$allNexdriveUrls|$seasonNum|$epNum"
                        episodes.add(newEpisode(dataStr) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = seasonNum
                        })
                    }
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

    private fun extractDownloadGroups(entry: Element): List<DownloadGroup> {
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

    // ============================================================
    //  Helper: Fetch nexdrive page and extract episode list
    // ============================================================
    //  Returns a list of (episodeNumber, listOfDownloadLinks) pairs.
    //  The nexdrive page has h4 headings like "-:Episodes: 1:-" followed
    //  by a <p> tag with download links (fastdl, filebee, etc.) for that episode.
    //
    private suspend fun resolveNexdriveEpisodes(url: String): List<Pair<Int, List<String>>> {
        return try {
            // Rewrite mobilejsr.rest → nexdrive.fit (Cloudflare blocks mobilejsr)
            val fixedUrl = url.replace("mobilejsr.rest", "nexdrive.fit")
            val doc = app.get(fixedUrl, headers = baseHeaders + ("Referer" to "$mainUrl/")).document
            val article = doc.selectFirst("article") ?: return emptyList<Pair<Int, List<String>>>()

            val episodes: MutableList<Pair<Int, List<String>>> = ArrayList()

            // Find all h4 headings that contain "Episodes"
            for (h4 in article.select("h4")) {
                val text = h4.text().trim()
                if (!text.contains("Episode", ignoreCase = true)) continue

                // Extract episode number from "-:Episodes: N:-" or "-:Episodes: 02:-"
                val epNum = Regex("""Episode[s]?\s*:\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue

                // Find the next sibling element that contains download links
                var sibling = h4.nextElementSibling()
                val links = mutableListOf<String>()
                var attempts = 0
                while (sibling != null && attempts < 3) {
                    for (a in sibling.select("a[href]")) {
                        val href = a.attr("href").trim()
                        if (href.isNotBlank() && !href.startsWith("#")) {
                            links.add(href)
                        }
                    }
                    if (links.isNotEmpty()) break
                    sibling = sibling.nextElementSibling()
                    attempts++
                }

                if (links.isNotEmpty()) {
                    episodes.add(Pair(epNum, links))
                }
            }

            episodes.toList()
        } catch (_: Exception) {
            emptyList<Pair<Int, List<String>>>()
        }
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
        // Match "Season 1", "S01", "S1", "Season.01"
        return Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\bS(\d+)\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
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
    //  Data string formats:
    //
    //  MOVIE: newline-separated nexdrive redirect URLs
    //    "https://nexdrive.fit/genxfm...//\nhttps://nexdrive.fit/genxfm...//"
    //    Each URL is a different quality (480p/720p/1080p).
    //    loadLinks fetches each, finds fastdl links, resolves to Google Drive.
    //
    //  TV EPISODE: pipe-separated nexdrive URLs + season + episode number
    //    "https://nexdrive.fit/genxfm...//|https://nexdrive.fit/genxfm...//|season|episode"
    //    The nexdrive URLs are different qualities for the same season.
    //    loadLinks fetches each nexdrive page, finds the specific episode by
    //    number (from h4 headings), and resolves its fastdl link.
    //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // Check if this is a TV episode (pipe-separated with season/episode at the end)
        // vs a movie (newline-separated URLs)
        val parts = data.split("|")
        val isTvEpisode = parts.size >= 3 &&
            parts[parts.size - 2].toIntOrNull() != null &&
            parts[parts.size - 1].toIntOrNull() != null

        if (isTvEpisode) {
            // TV episode: last 2 parts are season and episode number
            val seasonNum = parts[parts.size - 2].toInt()
            val episodeNum = parts[parts.size - 1].toInt()
            val nexdriveUrls = parts.dropLast(2).filter { it.isNotBlank() }

            var foundAny = false
            for (nexdriveUrl in nexdriveUrls) {
                try {
                    // Fetch the nexdrive page and find the specific episode
                    val episodeLinks = resolveNexdriveEpisodeLinks(nexdriveUrl, episodeNum)
                    for (link in episodeLinks) {
                        try {
                            if (link.contains("fastdl")) {
                                val directUrl = resolveFastDl(link)
                                if (directUrl != null) {
                                    val qualityLabel = getQualityFromUrl(nexdriveUrl)
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "TheMoviesFlix",
                                            name = "G-Direct [FastDL] $qualityLabel",
                                            url = directUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    foundAny = true
                                }
                            } else if (link.contains("filebee") || link.contains("filepress")) {
                                val directUrl = resolveFilePress(link)
                                if (directUrl != null) {
                                    val qualityLabel = getQualityFromUrl(nexdriveUrl)
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "TheMoviesFlix",
                                            name = "G-Direct [FilePress] $qualityLabel",
                                            url = directUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    foundAny = true
                                }
                            } else {
                                val loaded = loadExtractor(link, "https://nexdrive.fit/", subtitleCallback, callback)
                                if (loaded) foundAny = true
                            }
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
            return foundAny
        } else {
            // Movie: newline-separated nexdrive URLs
            val redirectUrls = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (redirectUrls.isEmpty()) return false

            var foundAny = false
            for (redirectUrl in redirectUrls) {
                try {
                    val links = resolveRedirectPage(redirectUrl)
                    for (link in links) {
                        try {
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
                            } else if (link.contains("filebee") || link.contains("filepress")) {
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
                            } else {
                                val loaded = loadExtractor(link, "https://nexdrive.fit/", subtitleCallback, callback)
                                if (loaded) foundAny = true
                            }
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
            return foundAny
        }
    }

    // ============================================================
    //  Helper: Extract quality label from nexdrive URL context
    // ============================================================
    private fun getQualityFromUrl(url: String): String {
        // We can't know the quality from the URL alone, but we can return empty
        // and let the user pick. The quality info is in the nexdrive page title.
        return ""
    }

    // ============================================================
    //  Helper: Fetch nexdrive page and extract download links for a SPECIFIC episode
    // ============================================================
    //  The nexdrive page has h4 headings like "-:Episodes: N:-" followed by
    //  a <p> tag with download links. This function finds the links for the
    //  requested episode number.
    //
    private suspend fun resolveNexdriveEpisodeLinks(url: String, episodeNum: Int): List<String> {
        return try {
            val fixedUrl = url.replace("mobilejsr.rest", "nexdrive.fit")
            val doc = app.get(fixedUrl, headers = baseHeaders + ("Referer" to "$mainUrl/")).document
            val article = doc.selectFirst("article") ?: return emptyList()

            // Find the h4 heading for the requested episode
            for (h4 in article.select("h4")) {
                val text = h4.text().trim()
                if (!text.contains("Episode", ignoreCase = true)) continue

                // Use the same regex with leading-zero stripping as resolveNexdriveEpisodes
                val epNum = Regex("""Episode[s]?\s*:\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue

                if (epNum != episodeNum) continue

                // Found the right episode — extract links from the next sibling element
                var sibling = h4.nextElementSibling()
                val links = mutableListOf<String>()
                var attempts = 0
                while (sibling != null && attempts < 3) {
                    for (a in sibling.select("a[href]")) {
                        val href = a.attr("href").trim()
                        if (href.isNotBlank() && !href.startsWith("#")) {
                            links.add(href)
                        }
                    }
                    if (links.isNotEmpty()) break
                    sibling = sibling.nextElementSibling()
                    attempts++
                }
                return links
            }

            // If no episode headings found, return ALL download links on the page
            // (handles the case where the nexdrive page is for a single episode/complete season)
            val allLinks = mutableListOf<String>()
            for (a in article.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.isNotBlank() && !href.startsWith("#")) {
                    allLinks.add(href)
                }
            }
            allLinks
        } catch (_: Exception) {
            emptyList()
        }
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
