package com.laddu100

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

class TwoDHiveProvider : MainAPI() {
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
        val headerDiv = header?.parent()?.parent() // Outer container flex div
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = quickGet("$mainUrl/?q=$encodedQuery")
        val soup = Jsoup.parse(html)
        return parseGrid(soup)
    }

    override suspend fun load(url: String): LoadResponse? {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val parts = data.split("|")
        if (parts.size < 2) return@coroutineScope false
        val epUrl = parts[0]
        val type = parts[1] // "sub" or "dub"

        val html = quickGet(epUrl)
        val soup = Jsoup.parse(html)

        // Find MultiServerPlayer props in Astro Island
        val island = soup.select("astro-island").firstOrNull { 
            it.attr("component-url").contains("MultiServerPlayer", ignoreCase = true) 
        } ?: return@coroutineScope false

        val propsStr = island.attr("props").takeIf { it.isNotEmpty() } ?: return@coroutineScope false

        val props = mapper.readTree(propsStr)
        val decoded = decodeAstro(props)

        // Robust MAL ID parsing with fallback to epUrl query parameter
        val malId = decoded.get("animeIdOrName")?.let { node ->
            if (node.isNumber) node.asInt() else node.asText().toIntOrNull()
        } ?: epUrl.substringAfter("anime=").substringBefore("&").toIntOrNull()

        val epNum = decoded.get("epNum")?.asInt() ?: 1
        val serversList = decoded.get("servers")

        val loadedResults = mutableListOf<Deferred<Boolean>>()

        // 1. Process parsed servers from page props
        if (serversList != null && serversList.isArray) {
            serversList.forEach { serverItem ->
                val serverName = serverItem.get("server_name")?.asText()?.trim() ?: ""
                val slug = serverItem.get("slug")?.asText()?.trim() ?: ""
                val isDub = serverItem.get("dub")?.asBoolean() ?: false
                val animeName = serverItem.get("anime_name")?.asText()?.trim() ?: ""

                // Robust check for dub status based on dub flag, server name, or anime name
                val isServerDub = isDub || serverName.contains("dub", ignoreCase = true) || animeName.contains("dub", ignoreCase = true)

                // Filter subbed/dubbed server according to the selected tab
                if ((type == "dub" && isServerDub) || (type == "sub" && !isServerDub)) {
                    if (slug.isNotEmpty()) {
                        loadedResults.add(async {
                            try {
                                when {
                                    serverName.equals("hadfree", ignoreCase = true) -> {
                                        val apiRespText = app.get(
                                            url = "$mainUrl/api/hadfree?slug=${slug}",
                                            headers = mapOf(
                                                "User-Agent" to userAgent,
                                                "Referer" to epUrl
                                            )
                                        ).text
                                        val apiJson = mapper.readTree(apiRespText)
                                        val streamUrl = apiJson.get("streamUrl")?.asText()
                                        if (!streamUrl.isNullOrEmpty()) {
                                            callback(
                                                newExtractorLink(
                                                    source = "hadfree",
                                                    name = "HAdfree",
                                                    url = streamUrl,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "$mainUrl/"
                                                }
                                            )
                                            true
                                        } else false
                                    }
                                    serverName.equals("neko_mp4", ignoreCase = true) -> {
                                        callback(
                                            newExtractorLink(
                                                source = "neko_mp4",
                                                name = "Neko MP4",
                                                url = slug,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    }
                                    serverName.equals("mp4upload", ignoreCase = true) -> {
                                        loadExtractor(
                                            url = "https://www.mp4upload.com/embed-$slug.html",
                                            referer = epUrl,
                                            subtitleCallback = subtitleCallback,
                                            callback = callback
                                        )
                                    }
                                    serverName.equals("meta_media_id", ignoreCase = true) -> {
                                        loadExtractor(
                                            url = "https://www.facebook.com/video/embed?video_id=$slug",
                                            referer = epUrl,
                                            subtitleCallback = subtitleCallback,
                                            callback = callback
                                        )
                                    }
                                    serverName.equals("hydrax", ignoreCase = true) || serverName.equals("abyssplayer", ignoreCase = true) -> {
                                        loadExtractor(
                                            url = "https://abyssplayer.com/$slug",
                                            referer = epUrl,
                                            subtitleCallback = subtitleCallback,
                                            callback = callback
                                        )
                                    }
                                    slug.startsWith("http://") || slug.startsWith("https://") -> {
                                        if (slug.contains(".mp4") || slug.contains(".m3u8")) {
                                            callback(
                                                newExtractorLink(
                                                    source = serverName.takeIf { it.isNotEmpty() } ?: "Direct",
                                                    name = serverName.takeIf { it.isNotEmpty() } ?: "Direct Link",
                                                    url = slug,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "$mainUrl/"
                                                }
                                            )
                                            true
                                        } else {
                                            loadExtractor(
                                                url = slug,
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
        }

        // 2. Extra servers if MAL ID is available
        if (malId != null) {
            // hiAnime (Only for subbed tab)
            if (type == "sub") {
                loadedResults.add(async {
                    try {
                        val apiRespText = app.get(
                            url = "$mainUrl/api/hianime?mal_id=$malId&ep_num=$epNum",
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to epUrl
                            )
                        ).text
                        val apiJson = mapper.readTree(apiRespText)
                        val m3u8 = apiJson.get("m3u8")?.asText()
                        val subtitleUrl = apiJson.get("subtitle")?.asText()
                        if (!m3u8.isNullOrEmpty()) {
                            if (!subtitleUrl.isNullOrEmpty()) {
                                subtitleCallback(
                                    newSubtitleFile("English", subtitleUrl) {
                                        this.headers = mapOf("Referer" to "https://megaplay.buzz/")
                                    }
                                )
                            }
                            M3u8Helper.generateM3u8(
                                source = "hiAnime",
                                streamUrl = m3u8,
                                referer = "https://megaplay.buzz/",
                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to "https://megaplay.buzz/"
                                )
                            ).forEach(callback)
                            true
                        } else {
                            false
                        }
                    } catch (e: Exception) {
                        false
                    }
                })
            }

            // MegaPlay (Sub / Dub depending on the selected tab)
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
                        val m3u8Url = sourcesJson.get("sources")?.get("file")?.asText()

                        if (!m3u8Url.isNullOrEmpty()) {
                            M3u8Helper.generateM3u8(
                                source = if (type == "sub") "MegaPlay Sub" else "MegaPlay Dub",
                                streamUrl = m3u8Url,
                                referer = "https://megaplay.buzz",
                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to "https://megaplay.buzz/"
                                )
                            ).forEach(callback)

                            sourcesJson.get("tracks")?.forEach { track ->
                                val file = track.get("file")?.asText() ?: return@forEach
                                val kind = track.get("kind")?.asText() ?: ""
                                val label = track.get("label")?.asText() ?: "Subtitle"
                                if (kind == "captions" || kind == "subtitles") {
                                    subtitleCallback(
                                        newSubtitleFile(label, file) {
                                            this.headers = mapOf("Referer" to "https://megaplay.buzz/")
                                        }
                                    )
                                }
                            }
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
