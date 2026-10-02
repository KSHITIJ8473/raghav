package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TheMoviesFlix : MainAPI() {
    override var mainUrl = "https://themoviesflixhq.com"
    override var name = "TheMoviesFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest Movies & Series",
        "category/web-series" to "Web Series",
        "category/hindi-dubbed-movies" to "Hindi Dubbed Movies",
        "category/english" to "English Movies",
        "category/bollywood" to "Bollywood Movies",
        "category/dual-audio-movies" to "Dual Audio Movies",
        "category/netflix" to "Netflix",
        "category/amazon-prime-video" to "Amazon Prime Video",
        "category/jiohotstar" to "JioHotstar",
        "category/disney-plus-hotstar" to "Disney+ Hotstar",
        "category/2160p" to "4K UHD"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val titleRaw = attr("title").ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty().ifBlank { return null }
        }
        val title = cleanTitle(titleRaw)
        val img = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        val quality = getSearchQuality(titleRaw)

        val isSeries = titleRaw.contains("Season", true) ||
            titleRaw.contains("Series", true) ||
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
        var t = raw.replace(Regex("^Download\\s*", RegexOption.IGNORE_CASE), "")
        listOf(
            " 480p", " 720p", " 1080p", " 2160p", " {Hindi", " {English",
            " (Hindi", " (English", " Hindi Dubbed", " Dual Audio",
            " [480p", " [720p", " [1080p", " Web Dl", " WEB-DL",
            " BluRay", " Blu-Ray", " Full Movie", " Complete"
        ).forEach { t = t.substringBefore(it) }
        return t.trim().trimEnd('(', '-', ':', '{')
    }

    private fun getSearchQuality(text: String): SearchQuality? = when {
        text.contains("2160p", true) || text.contains("4K", true) || text.contains("UHD", true) -> SearchQuality.FourK
        text.contains("1080p", true) -> SearchQuality.HD
        text.contains("720p", true) || text.contains("480p", true) -> SearchQuality.SD
        else -> null
    }

    private fun anchorsOf(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { it.toSearchResponse() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val domain = TmfNet.domain()
        val path = request.data
        val url = if (path.isBlank()) {
            if (page > 1) "$domain/page/$page/" else "$domain/"
        } else {
            if (page > 1) "$domain/$path/page/$page/" else "$domain/$path/"
        }
        val doc = TmfNet.fetchPage(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = anchorsOf(doc)
        val hasNext = doc.select("div.navigation a.nextpostslink, div.navigation li a:contains(Next)").isNotEmpty() ||
            items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val domain = TmfNet.domain()
        val doc = TmfNet.fetchPage("$domain/?s=${URLEncoder.encode(query, "UTF-8")}")
            ?: return emptyList()
        return anchorsOf(doc)
    }

    private data class DownloadGroup(val label: String, val redirectUrl: String)

    // whole season packs arrive as zip archives, they are not playable so
    // the button and its group are dropped before the drive page is fetched
    private val packRegex = Regex("""(?i)\b(zip|rar|7z|batch)\b""")

    private fun extractDownloadGroups(entry: Element): List<DownloadGroup> {
        return entry.select("div.mfx-download-group").flatMap { div ->
            val label = div.selectFirst("h3")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (packRegex.containsMatchIn(label)) return@flatMap emptyList()
            div.select("a[href]").mapNotNull { a ->
                val text = a.text().trim()
                val href = a.attr("href").trim()
                when {
                    !href.startsWith("http") -> null
                    text.contains("Batch", true) || text.contains("Zip", true) -> null
                    packRegex.containsMatchIn(text) -> null
                    else -> DownloadGroup(label, href)
                }
            }
        }.distinctBy { it.redirectUrl }
    }

    private fun extractSeasonNumber(text: String): Int? =
        Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\bS(\d+)\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val doc = TmfNet.fetchPage(url) ?: return null
        val entry = doc.selectFirst("div.entry-content") ?: return null

        val titleRaw = doc.selectFirst("h2.mfx-main-title")?.text()
            ?: doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: return null
        val title = cleanTitle(titleRaw)
        val pageTitle = doc.selectFirst("title")?.text() ?: ""

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = entry.selectFirst("div.mfx-plot-box")?.text()?.trim()
        val year = extractYear(entry) ?: extractYearFromTitle(titleRaw)
        val genres = extractListFromInfo(entry, "Genres")
        val cast = extractListFromInfo(entry, "Cast").map { ActorData(Actor(it)) }
        val runtime = extractFieldFromInfo(entry, "Runtime")?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val imdbLink = entry.selectFirst("div.mfx-imdb a[href*=imdb]")?.attr("href") ?: ""
        val imdbId = Regex("""title/(tt\d+)""").find(imdbLink)?.groupValues?.get(1)
        val rating = Regex("""([\d.]+)/10""").find(
            entry.selectFirst("div.mfx-imdb a")?.text() ?: ""
        )?.groupValues?.get(1)?.toFloatOrNull()

        val trailerId = entry.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")
        val trailer = trailerId?.let { "https://www.youtube.com/watch?v=$it" }

        val downloadGroups = extractDownloadGroups(entry)

        val allText = titleRaw + " " + pageTitle + " " + url + " " +
            downloadGroups.joinToString(" ") { it.label }
        val isSeries = allText.contains("Season", true) ||
            allText.contains("Series", true) ||
            allText.contains("TV Show", true) ||
            allText.contains("Episode", true) ||
            Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(allText)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonGroups = mutableMapOf<Int, MutableList<DownloadGroup>>()
            for (group in downloadGroups) {
                val seasonNum = extractSeasonNumber(group.label) ?: 1
                seasonGroups.getOrPut(seasonNum) { mutableListOf() }.add(group)
            }

            for ((seasonNum, groups) in seasonGroups) {
                if (groups.isEmpty()) continue
                val allDriveUrls = groups.joinToString("|") { it.redirectUrl }

                // every quality group lists the same episodes, the union of
                // all drive pages is the safest episode count
                val episodeNums = coroutineScope {
                    groups.map { group ->
                        async(Dispatchers.IO) {
                            TmfNet.fetchDrivePage(group.redirectUrl)?.episodes?.keys ?: emptySet()
                        }
                    }.awaitAll()
                }.flatten().toSortedSet()

                if (episodeNums.isEmpty()) {
                    episodes.add(newEpisode("$allDriveUrls|$seasonNum|1") {
                        this.name = groups.first().label
                        this.episode = 1
                        this.season = seasonNum
                    })
                } else {
                    for (epNum in episodeNums) {
                        episodes.add(newEpisode("$allDriveUrls|$seasonNum|$epNum") {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val parts = data.split("|")
        val isTvEpisode = parts.size >= 3 &&
            parts[parts.size - 2].toIntOrNull() != null &&
            parts[parts.size - 1].toIntOrNull() != null

        val episodeNum: Int?
        val driveUrls: List<String>
        if (isTvEpisode) {
            episodeNum = parts.last().toInt()
            driveUrls = parts.dropLast(2).filter { it.isNotBlank() }
        } else {
            episodeNum = null
            driveUrls = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        }
        if (driveUrls.isEmpty()) return false

        return coroutineScope {
            driveUrls.map { driveUrl ->
                async(Dispatchers.IO) {
                    try {
                        val page = TmfNet.fetchDrivePage(driveUrl) ?: return@async false
                        val hrefs = if (episodeNum != null) {
                            page.episodes[episodeNum] ?: emptyList()
                        } else {
                            page.links
                        }
                        if (hrefs.isEmpty()) return@async false
                        TmfSources.emitAll(
                            hrefs, page.quality, page.info,
                            "https://nexdrive.fit/", subtitleCallback, callback
                        )
                    } catch (_: Exception) {
                        false
                    }
                }
            }.awaitAll().any { it }
        }
    }

    private fun extractYear(entry: Element): Int? =
        entry.selectFirst("div.mfx-info-box li:contains(Release Year)")?.text()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun extractYearFromTitle(title: String): Int? =
        Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractFieldFromInfo(entry: Element, fieldName: String): String? =
        entry.selectFirst("div.mfx-info-box li:contains($fieldName)")?.text()
            ?.replace(Regex("$fieldName:\\s*", RegexOption.IGNORE_CASE), "")?.trim()

    private fun extractListFromInfo(entry: Element, fieldName: String): List<String> =
        extractFieldFromInfo(entry, fieldName)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
}
