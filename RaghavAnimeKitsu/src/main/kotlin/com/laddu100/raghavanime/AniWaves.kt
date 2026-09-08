package com.laddu100.raghavanime

import com.lagradost.api.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        mainUrl = FirebaseDomainHelper.getDomain("aniwaves") ?: mainUrl
        Log.d("RaghavAnime", "[AniWaves] getMainPage page=$page name='${request.name}' data=${request.data} mainUrl=$mainUrl")
        val category = request.data
        val url = "$mainUrl/home"
        val doc = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        val items = doc.select(".ani.items .item")
        Log.d("RaghavAnime", "[AniWaves] getMainPage parsed items=${items.size}")
        for (item in items) {
            val aTag = item.selectFirst(".poster a") ?: continue
            val href = fixUrl(aTag.attr("href"))
            val img = item.selectFirst(".poster img")?.attr("src") ?: ""
            val title = item.selectFirst(".info .name")?.text()
                ?: item.selectFirst(".poster img")?.attr("alt")?.replace(Regex(" Japanese english subbed$"), "")
                ?: continue

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

        Log.d("RaghavAnime", "[AniWaves] getMainPage done home=${home.size}")
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("aniwaves") ?: mainUrl
        Log.d("RaghavAnime", "[AniWaves] search query='$query' mainUrl=$mainUrl")
        val url = "$mainUrl/filter?keyword=$query"
        val doc = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        val items = doc.select(".ani.items .item, .items .item")
        Log.d("RaghavAnime", "[AniWaves] search parsed items=${items.size}")
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

        Log.d("RaghavAnime", "[AniWaves] search done results=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("aniwaves") ?: mainUrl
        Log.d("RaghavAnime", "[AniWaves] load url=$url")
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
        Log.d("RaghavAnime", "[AniWaves] load title='$title' animeId=$animeId")

        val epResponse = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            )
        ).parsed<AjaxResponse>()
        Log.d("RaghavAnime", "[AniWaves] episode list ajax status=${epResponse.status} resultLen=${epResponse.result?.length}")

        val episodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val seenEp = mutableSetOf<Int>()

        if (epResponse.status?.toString() == "200" && epResponse.result != null) {
            val epDoc = Jsoup.parse(epResponse.result)
            val episodeElements = epDoc.select("li a[data-ids]")
            Log.d("RaghavAnime", "[AniWaves] episode elements=${episodeElements.size}")

            for (ep in episodeElements) {
                val epNum = ep.attr("data-num").toIntOrNull() ?: continue
                if (!seenEp.add(epNum)) continue
                val dataIds = ep.attr("data-ids").ifBlank { "$animeId&eps=$epNum" }
                val epName = ep.parent()?.attr("title")
                    ?.substringAfter("GMT - ", "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                if (ep.attr("data-sub") == "1") {
                    episodes.add(newEpisode("$url|sub|$animeId|$epNum|$dataIds") {
                        this.name = epName ?: "Episode $epNum"
                        this.episode = epNum
                    })
                }
                if (ep.attr("data-dub") == "1") {
                    dubEpisodes.add(newEpisode("$url|dub|$animeId|$epNum|$dataIds") {
                        this.name = epName ?: "Episode $epNum"
                        this.episode = epNum
                    })
                }
            }
        }

        Log.d("RaghavAnime", "[AniWaves] load done sub=${episodes.size} dub=${dubEpisodes.size}")
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            if (jpTitle != null) this.japName = jpTitle
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        Log.d("RaghavAnime", "[AniWaves] loadLinks data=${data.take(120)}")
        val parts = data.split("|")
        Log.d("RaghavAnime", "[AniWaves] loadLinks parts=${parts.size}")
        if (parts.size < 4) return@coroutineScope false

        val dubOrSub = parts[1]
        val animeId = parts[2]
        val epNum = parts[3]
        val dataIds = parts[4].replace("&amp;", "&")
        val watchUrl = parts[0]
        Log.d("RaghavAnime", "[AniWaves] loadLinks dubOrSub=$dubOrSub animeId=$animeId epNum=$epNum dataIds=$dataIds")

        val serverResponse = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to watchUrl
            )
        ).parsed<AjaxResponse>()
        Log.d("RaghavAnime", "[AniWaves] server list ajax status=${serverResponse.status} resultLen=${serverResponse.result?.length}")

        if (serverResponse.status?.toString() != "200" || serverResponse.result.isNullOrEmpty()) return@coroutineScope false

        val serverDoc = Jsoup.parse(serverResponse.result)

        val targetTypes = when (dubOrSub) {
            "dub" -> listOf("dub")
            "sub" -> listOf("sub", "ssub")
            else -> listOf("sub", "ssub", "dub")
        }
        Log.d("RaghavAnime", "[AniWaves] targetTypes=$targetTypes")

        var foundAnySources = false
        val seenUrls = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            Log.d("RaghavAnime", "[AniWaves] emitting link: ${link.name} -> ${link.url.take(120)}")
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

        Log.d("RaghavAnime", "[AniWaves] serversToLoad=${serversToLoad.size}")
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

                    Log.d("RaghavAnime", "[AniWaves] sources ajax ($displayName/$targetType) status=${sourceResponse.status}")
                    if (sourceResponse.status?.toString() != "200") return@async
                    val embedUrl = sourceResponse.result?.url ?: return@async
                    Log.d("RaghavAnime", "[AniWaves] embed ($displayName/$targetType): ${embedUrl.take(120)}")
                    if (embedUrl.isEmpty()) return@async

                    val isNew = synchronized(seenUrls) {
                        seenUrls.add(embedUrl)
                    }
                    if (!isNew) return@async

                    val loaded = when {
                        embedUrl.contains("echovideo") || embedUrl.contains("weneverbeenfree.com") || embedUrl.contains("filemoon") || embedUrl.contains("myvidplay.com") -> {
                            Log.d("RaghavAnime", "[AniWaves] route WebView for $displayName ($targetType): ${embedUrl.take(120)}")
                            AniWavesWebView("$displayName (${targetType.uppercase()})", embedUrl.baseUrl()).getUrl(embedUrl, watchUrl, subtitleCallback, linkCallback)
                            true
                        }
                        else -> {
                            Log.d("RaghavAnime", "[AniWaves] route loadExtractor for $displayName ($targetType): ${embedUrl.take(120)}")
                            loadExtractor(embedUrl, watchUrl, subtitleCallback, linkCallback)
                        }
                    }
                    Log.d("RaghavAnime", "[AniWaves] server $displayName ($targetType) loaded=$loaded")
                    if (loaded) {
                        synchronized(seenUrls) {
                            foundAnySources = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RaghavAnime", "[AniWaves] server $displayName ($targetType) failed: ${e.message}")
                }
            }
        }

        deferreds.awaitAll()
        Log.d("RaghavAnime", "[AniWaves] loadLinks done foundAnySources=$foundAnySources")
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
}
