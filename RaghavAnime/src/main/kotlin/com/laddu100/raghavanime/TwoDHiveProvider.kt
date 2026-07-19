package com.laddu100.raghavanime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred

class RaghavTwoDHive : MainAPI() {
    override var mainUrl = "https://2dhive.com"
    override var name = "2Dhive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "completed" to "Completed Classics",
        "top" to "Top Rated Anime"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private suspend fun quickGet(url: String, referer: String? = null): String {
        val headersMap = mutableMapOf("User-Agent" to userAgent)
        if (referer != null) {
            headersMap["Referer"] = referer
        } else {
            headersMap["Referer"] = "$mainUrl/"
        }
        return app.get(url = url, headers = headersMap).text
    }

    private fun parseGrid(soup: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        soup.select("a[href*=\"/anime?anime=\"]").forEach { a ->
            val href = a.attr("href")
            val title = a.selectFirst("h3")?.text()?.trim()
                ?: a.text().trim().replace("Play Now", "").trim()
            val img = a.selectFirst("img")
            val posterUrl = img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")

            results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            })
        }
        return results.distinctBy { it.url }
    }

    private fun parseSection(soup: Document, sectionName: String): List<SearchResponse> {
        val header = soup.select("h2").firstOrNull { it.text().contains(sectionName, ignoreCase = true) }
        val headerDiv = header?.parent()?.parent()
        val grid = headerDiv?.nextElementSibling()

        val results = mutableListOf<SearchResponse>()
        grid?.select("a[href*=\"/anime?anime=\"]")?.forEach { a ->
            val href = a.attr("href")
            val title = a.selectFirst("h3")?.text()?.trim()
                ?: a.text().trim().replace("Play Now", "").trim()
            val img = a.selectFirst("img")
            val posterUrl = img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")

            results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl/?list=${request.data}&page=$page"
        } else {
            "$mainUrl/?list=${request.data}"
        }
        val html = quickGet(url)
        val soup = Jsoup.parse(html)

        val items = if (page == 1) {
            val sectionName = if (request.data == "completed") "Completed Classics" else "Top Rated Anime"
            parseSection(soup, sectionName)
        } else {
            parseGrid(soup)
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = quickGet("$mainUrl/?q=$encodedQuery")
        val soup = Jsoup.parse(html)
        return parseGrid(soup)
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("twodhive") ?: mainUrl
        val html = quickGet(url)
        val soup = Jsoup.parse(html)

        val title = soup.selectFirst("h1")?.text()?.trim()
            ?: soup.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        val poster = soup.select("img.object-cover").firstOrNull {
            it.attr("src").contains("myanimelist.net", ignoreCase = true)
        }?.attr("src") ?: soup.selectFirst("meta[property=og:image]")?.attr("content")

        val summary = soup.selectFirst("p.text-zinc-300")?.text()?.trim() ?: ""
        val mainPlot = soup.selectFirst("p.text-zinc-400")?.text()?.trim() ?: ""
        val plot = if (summary.isNotEmpty()) "$summary\n\n$mainPlot" else mainPlot

        val genres = mutableListOf<String>()
        soup.select("div.text-sm").forEach { div ->
            if (div.selectFirst("span")?.text()?.contains("Genres", ignoreCase = true) == true) {
                div.select("a").forEach { a ->
                    genres.add(a.text().trim())
                }
            }
        }

        var year: Int? = null
        soup.select("div.text-sm").forEach { div ->
            val text = div.text()
            if (text.contains("Premiered", ignoreCase = true) || text.contains("Aired", ignoreCase = true)) {
                val match = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                if (match != null) {
                    year = match.groupValues[1].toIntOrNull()
                }
            }
        }

        val episodes = mutableListOf<Episode>()
        soup.select("a[href*=\"/episode?\"]").forEach { a ->
            val href = a.attr("href")
            val epNumMatch = Regex("""ep_num=(\d+)""").find(href)
            val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epTitle = "Episode $epNum"

            episodes.add(newEpisode("$mainUrl$href") {
                this.episode = epNum
                this.name = epTitle
            })
        }

        val subEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|sub") {
                this.episode = ep.episode
                this.name = ep.name
            }
        }
        val dubEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|dub") {
                this.episode = ep.episode
                this.name = ep.name
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private val mapper = ObjectMapper()

    private fun decodeAstro(node: JsonNode): JsonNode {
        if (node.isArray && node.size() == 2 && node.get(0).isNumber) {
            return decodeAstro(node.get(1))
        }
        if (node.isArray) {
            val arrayNode = mapper.createArrayNode()
            node.forEach { arrayNode.add(decodeAstro(it)) }
            return arrayNode
        }
        if (node.isObject) {
            val objectNode = mapper.createObjectNode()
            node.fields().forEach { (key, value) ->
                objectNode.set<JsonNode>(key, decodeAstro(value))
            }
            return objectNode
        }
        return node
    }

    private suspend fun getProxyUrl(malId: Int, epNum: Int): String {
        try {
            val apiRespText = app.get(
                url = "$mainUrl/api/hianime?mal_id=$malId&ep_num=$epNum",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )
            ).text
            val apiJson = mapper.readTree(apiRespText)
            val m3u8 = apiJson.get("m3u8")?.asText()
            if (!m3u8.isNullOrEmpty() && m3u8.contains("/m3u8-proxy")) {
                return m3u8.substringBefore("/m3u8-proxy") + "/m3u8-proxy"
            }
        } catch (e: Exception) {

        }
        return "https://anicloud-hls-proxy.n3779118.workers.dev/m3u8-proxy"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val parts = data.split("|")
        if (parts.size < 2) return@coroutineScope false
        val epUrl = parts[0]
        val type = parts[1]

        val html = quickGet(epUrl)
        val soup = Jsoup.parse(html)

        val island = soup.select("astro-island").firstOrNull {
            it.attr("component-url").contains("MultiServerPlayer", ignoreCase = true)
        } ?: return@coroutineScope false

        val propsStr = island.attr("props").takeIf { it.isNotEmpty() } ?: return@coroutineScope false

        val props = mapper.readTree(propsStr)
        val decoded = decodeAstro(props)

        val malId = decoded.get("animeIdOrName")?.let { node ->
            if (node.isNumber) node.asInt() else node.asText().toIntOrNull()
        } ?: epUrl.substringAfter("anime=").substringBefore("&").toIntOrNull()

        val epNum = decoded.get("epNum")?.asInt() ?: 1
        val serversList = decoded.get("servers")

        val loadedResults = mutableListOf<Deferred<Boolean>>()

        if (serversList != null && serversList.isArray) {
            serversList.forEach { serverItem ->
                val serverName = serverItem.get("server_name")?.asText()?.trim() ?: ""
                val slug = serverItem.get("slug")?.asText()?.trim() ?: ""
                val isDub = serverItem.get("dub")?.asBoolean() ?: false
                val animeName = serverItem.get("anime_name")?.asText()?.trim() ?: ""

                val mappedServerName = if (serverName.equals("hydrax", ignoreCase = true)) "hadfree" else serverName

                val isServerDub = isDub || mappedServerName.contains("dub", ignoreCase = true) || animeName.contains("dub", ignoreCase = true)

                if ((type == "dub" && isServerDub) || (type == "sub" && !isServerDub)) {
                    loadedResults.add(async {
                        try {
                            when {
                                mappedServerName.equals("hadfree", ignoreCase = true) -> {
                                    val apiResp = app.get(
                                        url = "$mainUrl/api/hadfree?slug=$slug",
                                        headers = mapOf(
                                            "User-Agent" to userAgent,
                                            "Referer" to "$mainUrl/"
                                        )
                                    ).text
                                    val streamUrl = mapper.readTree(apiResp).get("streamUrl")?.asText()
                                    if (!streamUrl.isNullOrEmpty()) {
                                        callback(
                                            newExtractorLink(
                                                source = "Hadfree",
                                                name = "Hadfree",
                                                url = streamUrl,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.headers = mapOf(
                                                    "User-Agent" to userAgent,
                                                    "Referer" to "$mainUrl/"
                                                )
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    } else {
                                        false
                                    }
                                }
                                mappedServerName.equals("mp4upload", ignoreCase = true) -> {
                                    loadExtractor(
                                        url = "https://www.mp4upload.com/embed-$slug.html",
                                        referer = epUrl,
                                        subtitleCallback = subtitleCallback,
                                        callback = callback
                                    )
                                }
                                mappedServerName.equals("meta_media_id", ignoreCase = true) -> {
                                    loadExtractor(
                                        url = "https://www.facebook.com/video/embed?video_id=$slug",
                                        referer = epUrl,
                                        subtitleCallback = subtitleCallback,
                                        callback = callback
                                    )
                                }
                                mappedServerName.equals("abyssplayer", ignoreCase = true) -> {
                                    loadExtractor(
                                        url = "https://abyssplayer.com/$slug",
                                        referer = epUrl,
                                        subtitleCallback = subtitleCallback,
                                        callback = callback
                                    )
                                }
                                slug.startsWith("http://") || slug.startsWith("https://") -> {
                                    val encodedSlug = slug.replace(" ", "%20")
                                    if (encodedSlug.contains(".mp4") || encodedSlug.contains(".m3u8")) {
                                        callback(
                                            newExtractorLink(
                                                source = mappedServerName.takeIf { it.isNotEmpty() } ?: "Direct",
                                                name = mappedServerName.takeIf { it.isNotEmpty() } ?: "Direct Link",
                                                url = encodedSlug,
                                                type = if (encodedSlug.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.headers = mapOf(
                                                    "User-Agent" to userAgent,
                                                    "Referer" to "$mainUrl/"
                                                )
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    } else {
                                        loadExtractor(
                                            url = encodedSlug,
                                            referer = epUrl,
                                            subtitleCallback = subtitleCallback,
                                            callback = callback
                                        )
                                    }
                                }
                                else -> false
                            }
                        } catch (e: Exception) {
                            false
                        }
                    })
                }
            }
        }

        if (malId != null) {

            loadedResults.add(async {
                try {
                    val megaplayUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$type"
                    val playerPageHtml = app.get(
                        url = megaplayUrl,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to epUrl
                        )
                    ).text

                    val playerPageSoup = Jsoup.parse(playerPageHtml)
                    val playerId = playerPageSoup.selectFirst("#megaplay-player")?.attr("data-id")
                        ?: Regex("""data-id=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: playerPageSoup.selectFirst("#megaplay-player")?.attr("data-realid")
                        ?: Regex("""data-realid=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: Regex("""/stream/s-\d+/(\d+)""").find(megaplayUrl)?.groupValues?.get(1)

                    if (playerId != null) {
                        val sourcesText = app.get(
                            url = "https://megaplay.buzz/stream/getSources?id=$playerId&type=$type",
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to megaplayUrl,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to "https://megaplay.buzz"
                            )
                        ).text

                        val sourcesJson = mapper.readTree(sourcesText)
                        val sources = sourcesJson.get("sources")
                        val m3u8Url = if (sources != null && sources.isArray) {
                            sources.get(0)?.get("file")?.asText()
                        } else {
                            sources?.get("file")?.asText()
                        }

                        val tracks = sourcesJson.get("tracks")
                        if (tracks != null && tracks.isArray) {
                            tracks.forEach { track ->
                                val file = track.get("file")?.asText() ?: return@forEach
                                val label = track.get("label")?.asText() ?: "Unknown"
                                subtitleCallback(SubtitleFile(label, file))
                            }
                        }

                        if (!m3u8Url.isNullOrEmpty()) {
                            val displayName = if (type == "sub") "MegaPlay Sub" else "MegaPlay Dub"

                            callback(
                                newExtractorLink(
                                    source = displayName,
                                    name = "$displayName (Direct)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Referer" to "https://megaplay.buzz/",
                                        "Origin" to "https://megaplay.buzz"
                                    )
                                    this.referer = "https://megaplay.buzz/"
                                }
                            )

                            val proxyPrefix = getProxyUrl(malId, epNum)
                            val encodedTarget = URLEncoder.encode(m3u8Url, "UTF-8")
                            val encodedHeaders = URLEncoder.encode("{\"referer\":\"https://megaplay.buzz/\"}", "UTF-8")
                            val wrappedUrl = "$proxyPrefix?url=$encodedTarget&headers=$encodedHeaders"

                            callback(
                                newExtractorLink(
                                    source = displayName,
                                    name = "$displayName (Proxy)",
                                    url = wrappedUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Referer" to "https://megaplay.buzz/"
                                    )
                                    this.referer = "https://megaplay.buzz/"
                                }
                            )
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            })
        }

        loadedResults.awaitAll().any { it }
    }
}
