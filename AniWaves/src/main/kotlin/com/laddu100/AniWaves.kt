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
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class AniWaves : MainAPI() {
    override var mainUrl = "https://aniwaves.ru"
    override var name = "AniWaves"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val serverNames = mapOf(
        "4" to "Vidplay",
        "1" to "BYFMS",
        "2" to "DGHG",
        "12" to "MyCloud",
        "14" to "DatSaV"
    )

    // Browse paths verified on the live site. Path sections paginate via
    // "/<path>/page/N", filter sections via "?lang=...&page=N".
    override val mainPage = mainPageOf(
        "updated" to "Recently Updated",
        "trending" to "Trending",
        "filter?lang=sub" to "Latest Sub",
        "filter?lang=dub" to "Latest Dub",
        "newest" to "New Release",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("aniwaves") ?: mainUrl
        val path = request.data
        val url = if (path.startsWith("filter")) {
            "$mainUrl/$path&page=$page"
        } else {
            "$mainUrl/$path/page/$page"
        }

        val doc = app.get(url).document
        val home = doc.select(".ani.items .item").mapNotNull { it.toSearchResponse() }

        // The pagination bar links to the final page with "»", so any link
        // pointing past the current page means more pages exist.
        val hasNext = doc.select("ul.pagination a.page-link").mapNotNull { link ->
            pagePattern.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.any { it > page }

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("aniwaves") ?: mainUrl
        val url = "$mainUrl/filter?keyword=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        return doc.select(".ani.items .item").mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = selectFirst(".poster a") ?: return null
        val title = selectFirst(".info .name")?.text()
            ?: selectFirst(".poster img")?.attr("alt")?.removeSuffix(" Japanese english subbed")
            ?: return null
        val typeStr = selectFirst(".meta .right")?.text()?.trim() ?: ""
        val tvType = when (typeStr.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }
        val subEps = selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dubEps = selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, fixUrl(aTag.attr("href")), tvType) {
            posterUrl = selectFirst(".poster img")?.attr("src") ?: ""
            addDubStatus(
                dubExist = dubEps != null && dubEps > 0,
                subExist = subEps != null && subEps > 0,
                dubEpisodes = dubEps,
                subEpisodes = subEps
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("aniwaves") ?: mainUrl
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.title")?.text() ?: return null
        val jpTitle = doc.selectFirst("h1.title")?.attr("data-jp")
        val posterUrl = doc.selectFirst(".poster img")?.attr("src")
        val backgroundUrl = doc.selectFirst(".hotest .image div")?.let {
            Regex("""url\('([^']+)'\)""").find(it.attr("style"))?.groupValues?.get(1)
        }
        val plot = doc.selectFirst(".synopsis .shorting")?.text()
            ?: doc.selectFirst(".synopsis")?.text()
        val year = doc.selectFirst(".bmeta .meta div:contains(Premiered) a")?.text()?.trim()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
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

        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: Regex("""-(\d+)$""").find(url)?.groupValues?.get(1)
            ?: return null

        val epResponse = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            )
        ).parsed<AjaxResponse>()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val seenEp = mutableSetOf<Int>()

        if (epResponse.status?.toString() == "200" && epResponse.result != null) {
            val epDoc = Jsoup.parse(epResponse.result)

            for (ep in epDoc.select("li a[data-ids]")) {
                val epNum = ep.attr("data-num").toIntOrNull() ?: continue
                if (!seenEp.add(epNum)) continue

                // data-ids holds the raw "id&eps=n" pair; the server-list
                // endpoint expects the & as a separate query param, so keep
                // it unencoded.
                val dataIds = ep.attr("data-ids").ifBlank { "$animeId&eps=$epNum" }
                // The human-readable episode name sits on the parent
                // <li title="Release: <date> GMT - <name>">.
                val epName = ep.parent()?.attr("title")
                    ?.substringAfter("GMT - ", "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                if (ep.attr("data-sub") == "1") {
                    subEpisodes.add(newEpisode("sub|$animeId|$epNum|$dataIds|$url") {
                        this.name = epName
                        this.episode = epNum
                    })
                }
                if (ep.attr("data-dub") == "1") {
                    dubEpisodes.add(newEpisode("dub|$animeId|$epNum|$dataIds|$url") {
                        this.name = epName
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
    ): Boolean = coroutineScope {
        val parts = data.split("|")
        if (parts.size < 4) return@coroutineScope false

        val dubOrSub = parts[0]
        val dataIds = parts[3]
        val watchUrl = parts.getOrNull(4) ?: "$mainUrl/watch/"

        val serverResponse = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to watchUrl
            )
        ).parsed<AjaxResponse>()

        if (serverResponse.status?.toString() != "200" || serverResponse.result.isNullOrEmpty()) return@coroutineScope false

        val serverDoc = Jsoup.parse(serverResponse.result)

        val targetTypes = when (dubOrSub) {
            "dub" -> listOf("dub")
            "sub" -> listOf("sub", "ssub")
            else -> listOf("sub", "ssub", "dub")
        }

        var foundAnySources = false
        val seenUrls = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            synchronized(seenUrls) {
                foundAnySources = true
            }
            callback(link)
        }

        val serversToLoad = mutableListOf<Triple<String, String, String>>()

        for (targetType in targetTypes) {
            val typeSection = serverDoc.selectFirst(".type[data-type=$targetType]") ?: continue
            for (serverLi in typeSection.select("li[data-link-id]")) {
                val linkId = serverLi.attr("data-link-id")
                val svId = serverLi.attr("data-sv-id")
                val serverName = serverLi.text().trim()
                val displayName = serverNames[svId] ?: serverName

                if (linkId.isNotEmpty()) {
                    serversToLoad.add(Triple(linkId, displayName, targetType))
                }
            }
        }

        if (serversToLoad.isEmpty()) return@coroutineScope false

        val deferreds = serversToLoad.map { (linkId, displayName, targetType) ->
            async {
                try {
                    val sourceResponse = app.get(
                        "$mainUrl/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to watchUrl
                        )
                    ).parsed<SourceResponse>()

                    if (sourceResponse.status?.toString() != "200") return@async
                    val embedUrl = sourceResponse.result?.url ?: return@async
                    if (embedUrl.isEmpty()) return@async

                    val isNew = synchronized(seenUrls) {
                        seenUrls.add(embedUrl)
                    }
                    if (!isNew) return@async

                    val loaded = when {
                        embedUrl.contains("echovideo") || embedUrl.contains("weneverbeenfree.com") || embedUrl.contains("filemoon") || embedUrl.contains("myvidplay.com") -> {
                            AniWavesWebView("$displayName (${targetType.uppercase()})", embedUrl.baseUrl()).getUrl(embedUrl, watchUrl, subtitleCallback, linkCallback)
                            true
                        }
                        else -> {
                            loadExtractor(embedUrl, watchUrl, subtitleCallback, linkCallback)
                        }
                    }
                    if (loaded) {
                        synchronized(seenUrls) {
                            foundAnySources = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        deferreds.awaitAll()
        return@coroutineScope foundAnySources
    }

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

    companion object {
        private val pagePattern = Regex("""(?:/page/|[?&]page=)(\d+)""")
    }
}
