package com.laddu100

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class AniWaves : MainAPI() {
    override var mainUrl = "https://aniwaves.ru"
    override var name = "AniWaves"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Server display names by sv-id
    private val serverNames = mapOf(
        "4" to "Vidplay",
        "1" to "BYFMS",
        "2" to "DGHG",
        "12" to "MyCloud"
    )

    override val mainPage = mainPageOf(
        "updated" to "Latest Episode",
        "subbed" to "Latest Sub",
        "dubbed" to "Latest Dub",
        "newest" to "New Release",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data
        // The homepage uses AJAX for different tabs, but we can scrape the main page
        val url = "$mainUrl/home"
        val doc = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        // Parse anime items from the page
        val items = doc.select(".ani.items .item")
        for (item in items) {
            val aTag = item.selectFirst(".poster a") ?: continue
            val href = fixUrl(aTag.attr("href"))
            val img = item.selectFirst(".poster img")?.attr("src") ?: ""
            val title = item.selectFirst(".info .name")?.text()
                ?: item.selectFirst(".poster img")?.attr("alt")?.replace(Regex(" Japanese english subbed$"), "")
                ?: continue

            // Extract sub/dub episode counts
            val subEps = item.selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
            val dubEps = item.selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()

            home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = img
                addDubStatus(
                    dubExist = dubEps != null && dubEps > 0,
                    subExist = subEps != null && subEps > 0,
                    dubEpisodes = dubEps,
                    subEpisodes = subEps
                )
            })
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        val items = doc.select(".ani.items .item, .items .item")
        for (item in items) {
            val aTag = item.selectFirst(".poster a") ?: continue
            val href = fixUrl(aTag.attr("href"))
            val img = item.selectFirst(".poster img")?.attr("src") ?: ""
            val title = item.selectFirst(".info .name")?.text()
                ?: item.selectFirst(".poster img")?.attr("alt")?.replace(Regex(" Japanese english subbed$"), "")
                ?: continue

            val subEps = item.selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
            val dubEps = item.selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()
            val typeStr = item.selectFirst(".meta .right")?.text()?.trim() ?: ""

            val tvType = when (typeStr.lowercase()) {
                "movie" -> TvType.AnimeMovie
                "ova", "ona", "special" -> TvType.OVA
                else -> TvType.Anime
            }

            results.add(newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = img
                addDubStatus(
                    dubExist = dubEps != null && dubEps > 0,
                    subExist = subEps != null && subEps > 0,
                    dubEpisodes = dubEps,
                    subEpisodes = subEps
                )
            })
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.title")?.text() ?: return null
        val jpTitle = doc.selectFirst("h1.title")?.attr("data-jp")
        val posterUrl = doc.selectFirst(".poster img")?.attr("src")
        val backgroundUrl = doc.selectFirst(".hotest .image div")?.let {
            Regex("url\\('([^']+)'\\)").find(it.attr("style"))?.groupValues?.get(1)
        }
        val plot = doc.selectFirst(".synopsis .shorting")?.text()
            ?: doc.selectFirst(".synopsis")?.text()
        val year = doc.selectFirst(".bmeta .meta div:contains(Premiered) a")?.text()?.trim()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = doc.select(".bmeta .meta div:contains(Genre) a").map { it.text() }
        val typeStr = doc.selectFirst(".bmeta .meta div:contains(Type) span")?.text()?.trim() ?: ""

        val tvType = when (typeStr.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }

        val status = doc.selectFirst(".bmeta .meta div:contains(Status) span")?.text()?.trim()
        val showStatus = when (status?.lowercase()) {
            "currently airing" -> ShowStatus.Ongoing
            "finished airing" -> ShowStatus.Completed
            else -> null
        }

        // Get anime ID from the page
        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: Regex("-(\\d+)$").find(url)?.groupValues?.get(1)
            ?: return null

        // Fetch episodes via AJAX
        val epResponse = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            )
        ).parsed<AjaxResponse>()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (epResponse.status?.toString() == "200" && epResponse.result != null) {
            val epDoc = Jsoup.parse(epResponse.result)
            val episodeElements = epDoc.select("li a[data-ids]")

            // De-duplicate by episode number (site sometimes has duplicates)
            val seenSub = mutableSetOf<Int>()
            val seenDub = mutableSetOf<Int>()

            for (ep in episodeElements) {
                val epNum = ep.attr("data-num").toIntOrNull() ?: continue
                val dataIds = "$animeId&eps=$epNum"
                val hasSub = ep.attr("data-sub") == "1"
                val hasDub = ep.attr("data-dub") == "1"

                // Episode data format: animeId|epNum|dataIds
                val episodeData = "$animeId|$epNum|$dataIds"

                if (hasSub && seenSub.add(epNum)) {
                    subEpisodes.add(newEpisode("sub|$episodeData") {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    })
                }

                if (hasDub && seenDub.add(epNum)) {
                    dubEpisodes.add(newEpisode("dub|$episodeData") {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    })
                }
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            if (jpTitle != null) this.japName = jpTitle
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Format: sub|animeId|epNum|dataIds  OR  dub|animeId|epNum|dataIds
        val parts = data.split("|")
        if (parts.size < 4) return false

        val dubOrSub = parts[0] // "sub" or "dub"
        val animeId = parts[1]
        val epNum = parts[2]
        val dataIds = parts[3] // e.g. "81553&eps=1"

        // Step 1: Get server list for this episode
        val serverResponse = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/watch/"
            )
        ).parsed<AjaxResponse>()

        if (serverResponse.status?.toString() != "200" || serverResponse.result.isNullOrEmpty()) return false

        val serverDoc = Jsoup.parse(serverResponse.result)

        // Step 2: Prioritize target type based on selection but include both sub and dub
        val targetTypes = if (dubOrSub == "dub") {
            listOf("dub", "sub", "ssub")
        } else {
            listOf("sub", "ssub", "dub")
        }

        var foundAnySources = false

        for (targetType in targetTypes) {
            val typeSection = serverDoc.selectFirst(".type[data-type=$targetType]") ?: continue

            for (serverLi in typeSection.select("li[data-link-id]")) {
                val linkId = serverLi.attr("data-link-id")
                val svId = serverLi.attr("data-sv-id")
                val serverName = serverLi.text().trim()
                val displayName = serverNames[svId] ?: serverName
                val typeLabel = targetType.uppercase()

                if (linkId.isEmpty()) continue

                try {
                    // Step 3: Get the embed URL from the sources endpoint
                    val sourceResponse = app.get(
                        "$mainUrl/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to "$mainUrl/watch/"
                        )
                    ).parsed<SourceResponse>()

                    if (sourceResponse.status?.toString() != "200") continue
                    val embedUrl = sourceResponse.result?.url ?: continue
                    if (embedUrl.isEmpty()) continue

                    val suffix = " ($typeLabel)"
                    val wrappedCallback = { link: ExtractorLink ->
                        val newName = if (link.name.contains(suffix)) link.name else "${link.name}$suffix"
                        callback(link.copy(name = newName))
                    }

                    val nameWithType = "$displayName$suffix"
                    val loaded = when {
                        embedUrl.contains("echovideo") || embedUrl.contains("weneverbeenfree.com") || embedUrl.contains("filemoon") || embedUrl.contains("myvidplay.com") -> {
                            AniWavesWebView(nameWithType, embedUrl.baseUrl()).getUrl(embedUrl, "$mainUrl/", subtitleCallback, callback)
                            true
                        }
                        else -> {
                            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                        }
                    }
                    if (loaded) {
                        foundAnySources = true
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return foundAnySources
    }

    // Data classes for AJAX responses
    data class AjaxResponse(
        val status: Any? = null,
        val result: String? = null
    )

    data class SourceResponse(
        val status: Any? = null,
        val result: SourceResult? = null
    )

    data class SourceResult(
        val url: String? = null,
        val server: Int? = null,
        val skip_data: SkipData? = null,
        val sources: List<Any>? = null,
        val tracks: List<Any>? = null
    )

    data class SkipData(
        val intro: List<Int>? = null,
        val outro: List<Int>? = null
    )

    private fun String.baseUrl(): String {
        return Regex("""https?://[^/]+""").find(this)?.value ?: mainUrl
    }
}
