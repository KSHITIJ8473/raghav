package com.laddu100

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newSubtitleFile
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
    private val mapper = ObjectMapper()

    private suspend fun quickGet(url: String, referer: String? = null): String {
        val headers = mutableMapOf("User-Agent" to userAgent)
        headers["Referer"] = referer ?: "$mainUrl/"
        return app.get(url = url, headers = headers).text
    }

    private fun parseGrid(soup: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        soup.select("a[href*=\"/anime?anime=\"]").forEach { a ->
            val href = a.attr("href")
            val title = a.selectFirst("h3 span.truncate")?.text()?.trim()
                ?: a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: ""
            if (title.length < 2) return@forEach

            var posterUrl: String? = null
            val img = a.selectFirst("img")
            if (img != null) {
                val src = img.attr("src").takeIf { it.isNotBlank() }
                if (src != null && (src.contains("anilist") || src.contains("myanimelist") || src.contains("tmdb"))) {
                    posterUrl = src
                }
            }
            if (posterUrl == null) {
                var parent = a.parent()
                repeat(5) {
                    if (parent != null && posterUrl == null) {
                        val pImg = parent!!.selectFirst("img")
                        if (pImg != null) {
                            val src = pImg.attr("src").takeIf { it.isNotBlank() }
                            if (src != null && (src.contains("anilist") || src.contains("myanimelist") || src.contains("tmdb"))) {
                                posterUrl = src
                            }
                        }
                    }
                    parent = parent?.parent()
                }
            }

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
        val items = parseGrid(soup)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = quickGet("$mainUrl/?q=$encodedQuery")
        val soup = Jsoup.parse(html)
        return parseGrid(soup)
    }

    private data class EpisodeMeta(
        val number: Int,
        val title: String?,
        val thumbnail: String?
    )

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

    override suspend fun load(url: String): LoadResponse? {
        val malId = url.substringAfter("anime=").substringBefore("&").substringBefore("/").toIntOrNull()
        val html = quickGet(url)
        val soup = Jsoup.parse(html)

        val title = soup.selectFirst("h1")?.text()?.trim()
            ?: soup.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        var poster: String? = null
        soup.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.contains("anilist") && src.contains("cover")) {
                poster = src
                return@forEach
            }
        }
        if (poster == null) {
            poster = soup.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var plot = ""
        val summaryLabel = soup.select("p").firstOrNull { it.text().trim() == "Synopsis" }
        if (summaryLabel != null) {
            val summaryP = summaryLabel.nextElementSibling()
            if (summaryP != null) {
                plot = summaryP.text().trim()
            }
        }
        if (plot.isBlank() && malId != null) {
            try {
                val apiResp = quickGet("$mainUrl/api/anime/summary?malId=$malId")
                val apiJson = mapper.readTree(apiResp)
                plot = apiJson.get("anime")?.get("synopsis")?.asText() ?: ""
            } catch (_: Exception) {}
        }

        val genres = mutableListOf<String>()
        var year: Int? = null
        if (malId != null) {
            try {
                val apiResp = quickGet("$mainUrl/api/anime/summary?malId=$malId")
                val apiJson = mapper.readTree(apiResp)
                val genresNode = apiJson.get("anime")?.get("genres")
                if (genresNode != null && genresNode.isArray) {
                    genresNode.forEach { g -> genres.add(g.asText()) }
                }
                year = apiJson.get("anime")?.get("year")?.asInt()
            } catch (_: Exception) {}
        }
        if (year == null) {
            soup.select("div, span, p, small").forEach { el ->
                val text = el.text()
                val match = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                if (match != null && (text.contains("Premiered", true) || text.contains("Aired", true) || text.contains("Year", true))) {
                    year = match.groupValues[1].toIntOrNull()
                }
            }
        }

        var totalEpisodes = 1
        var episodeMetaList = mutableListOf<EpisodeMeta>()

        val episodeBrowserIsland = soup.select("astro-island").firstOrNull {
            it.attr("component-url").contains("EpisodeBrowser", ignoreCase = true)
        }
        if (episodeBrowserIsland != null) {
            val propsStr = episodeBrowserIsland.attr("props").takeIf { it.isNotEmpty() }
            if (!propsStr.isNullOrEmpty()) {
                try {
                    val props = mapper.readTree(propsStr)
                    val decoded = decodeAstro(props)
                    totalEpisodes = decoded.get("totalEpisodes")?.asInt() ?: 1
                    val episodeMetaNode = decoded.get("episodeMeta")
                    if (episodeMetaNode != null && episodeMetaNode.isArray) {
                        episodeMetaNode.forEach { ep ->
                            val num = ep.get("number")?.asInt()
                            val epTitle = ep.get("title")?.asText()
                            val thumb = ep.get("thumbnail")?.asText()
                            if (num != null) {
                                episodeMetaList.add(EpisodeMeta(num, epTitle, thumb))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (episodeMetaList.isEmpty()) {
            soup.select("a[href*=\"/episode?\"]").forEach { a ->
                val href = a.attr("href")
                val epNumMatch = Regex("""ep_num=(\d+)""").find(href)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                episodeMetaList.add(EpisodeMeta(epNum, null, null))
            }
        }

        if (episodeMetaList.isEmpty() && totalEpisodes > 0) {
            for (i in 1..totalEpisodes.coerceAtMost(1000)) {
                episodeMetaList.add(EpisodeMeta(i, null, null))
            }
        }

        val episodes = episodeMetaList.map { ep ->
            val epUrl = "$mainUrl/episode?anime=${malId ?: ""}&ep_num=${ep.number}"
            newEpisode(epUrl) {
                this.episode = ep.number
                this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}"
                this.posterUrl = ep.thumbnail
            }
        }

        val subEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|sub") {
                this.episode = ep.episode
                this.name = ep.name
                this.posterUrl = ep.posterUrl
            }
        }
        val dubEpisodes = episodes.map { ep ->
            newEpisode("${ep.data}|dub") {
                this.episode = ep.episode
                this.name = ep.name
                this.posterUrl = ep.posterUrl
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

    private suspend fun getProxyUrl(malId: Int, epNum: Int): String {
        try {
            val apiRespText = app.get(
                url = "$mainUrl/api/hianime?mal_id=$malId&ep_num=$epNum",
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
            ).text
            val apiJson = mapper.readTree(apiRespText)
            val m3u8 = apiJson.get("m3u8")?.asText()
            if (!m3u8.isNullOrEmpty() && m3u8.contains("/m3u8-proxy")) {
                return m3u8.substringBefore("/m3u8-proxy") + "/m3u8-proxy"
            }
        } catch (_: Exception) {}
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
                                        headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                                    ).text
                                    val streamUrl = mapper.readTree(apiResp).get("streamUrl")?.asText()
                                    if (!streamUrl.isNullOrEmpty()) {
                                        callback(
                                            newExtractorLink("Hadfree", "Hadfree", streamUrl, type = ExtractorLinkType.VIDEO) {
                                                this.headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    } else false
                                }
                                mappedServerName.equals("mp4upload", ignoreCase = true) -> {
                                    loadExtractor("https://www.mp4upload.com/embed-$slug.html", epUrl, subtitleCallback, callback)
                                }
                                mappedServerName.equals("meta_media_id", ignoreCase = true) -> {
                                    loadExtractor("https://www.facebook.com/video/embed?video_id=$slug", epUrl, subtitleCallback, callback)
                                }
                                mappedServerName.equals("abyssplayer", ignoreCase = true) -> {
                                    loadExtractor("https://abyssplayer.com/$slug", epUrl, subtitleCallback, callback)
                                }
                                slug.startsWith("http://") || slug.startsWith("https://") -> {
                                    val encodedSlug = slug.replace(" ", "%20")
                                    if (encodedSlug.contains(".mp4") || encodedSlug.contains(".m3u8")) {
                                        callback(
                                            newExtractorLink(
                                                mappedServerName.ifEmpty { "Direct" },
                                                mappedServerName.ifEmpty { "Direct Link" },
                                                encodedSlug,
                                                type = if (encodedSlug.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
                                                this.referer = "$mainUrl/"
                                            }
                                        )
                                        true
                                    } else {
                                        loadExtractor(encodedSlug, epUrl, subtitleCallback, callback)
                                    }
                                }
                                else -> false
                            }
                        } catch (_: Exception) { false }
                    })
                }
            }
        }

        if (malId != null) {
            loadedResults.add(async {
                try {
                    val megaplayUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$type"
                    val playerPageHtml = app.get(megaplayUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to epUrl)).text
                    val playerPageSoup = Jsoup.parse(playerPageHtml)
                    val playerId = playerPageSoup.selectFirst("#megaplay-player")?.attr("data-id")
                        ?: Regex("""data-id=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: playerPageSoup.selectFirst("#megaplay-player")?.attr("data-realid")
                        ?: Regex("""data-realid=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: Regex("""/stream/s-\d+/(\d+)""").find(megaplayUrl)?.groupValues?.get(1)

                    if (playerId != null) {
                        val sourcesText = app.get(
                            "https://megaplay.buzz/stream/getSources?id=$playerId&type=$type",
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
                                subtitleCallback(newSubtitleFile(label, file) {
                                    this.headers = mapOf("Referer" to "https://megaplay.buzz/")
                                })
                            }
                        }

                        if (!m3u8Url.isNullOrEmpty()) {
                            val displayName = if (type == "sub") "MegaPlay Sub" else "MegaPlay Dub"
                            callback(
                                newExtractorLink(displayName, "$displayName (Direct)", m3u8Url, type = ExtractorLinkType.M3U8) {
                                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://megaplay.buzz/", "Origin" to "https://megaplay.buzz")
                                    this.referer = "https://megaplay.buzz/"
                                }
                            )

                            val proxyPrefix = getProxyUrl(malId, epNum)
                            val encodedTarget = URLEncoder.encode(m3u8Url, "UTF-8")
                            val encodedHeaders = URLEncoder.encode("{\"referer\":\"https://megaplay.buzz/\"}", "UTF-8")
                            val wrappedUrl = "$proxyPrefix?url=$encodedTarget&headers=$encodedHeaders"
                            callback(
                                newExtractorLink(displayName, "$displayName (Proxy)", wrappedUrl, type = ExtractorLinkType.M3U8) {
                                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://megaplay.buzz/")
                                    this.referer = "https://megaplay.buzz/"
                                }
                            )
                            true
                        } else false
                    } else false
                } catch (_: Exception) { false }
            })
        }

        loadedResults.awaitAll().any { it }
    }
}
