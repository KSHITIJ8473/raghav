package com.laddu100.raghavanime

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AniSugeProvider : MainAPI() {
    override var mainUrl = "https://anisuge.tv"
    override var name = "AniSuge"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "recent" to "Recently Updated",
        "added" to "Recently Added",
        "complete" to "Just Completed",
        "mostview" to "Most Viewed"
    )

    private val cfKiller = CloudflareKiller()
    private val kwikExtractor by lazy { KwikExtractor() }

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        var i = 0
        j = 0
        val result = ByteArray(input.size)
        for (x in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            val k = s[(s[i] + s[j]) and 0xFF]
            result[x] = ((input[x].toInt() and 0xFF) xor k).toByte()
        }
        return result
    }

    private fun shiftCharcode(t: String): ByteArray {
        val result = ByteArray(t.length)
        for (r in t.indices) {
            var s = t[r].code
            val mod = r % 8
            when (mod) {
                1 -> s += 3
                7 -> s += 5
                2 -> s -= 4
                4 -> s -= 2
                6 -> s += 4
                0 -> s -= 3
                3 -> s += 2
                5 -> s += 5
            }
            result[r] = s.toByte()
        }
        return result
    }

    private fun rot13(s: String): String {
        val result = StringBuilder()
        for (c in s) {
            when (c) {
                in 'a'..'z' -> {
                    var o = c.code + 13
                    if (o > 'z'.code) o -= 26
                    result.append(o.toChar())
                }
                in 'A'..'Z' -> {
                    var o = c.code + 13
                    if (o > 'Z'.code) o -= 26
                    result.append(o.toChar())
                }
                else -> result.append(c)
            }
        }
        return result.toString()
    }

    // vrf token the ajax endpoints expect, matching the site's client js
    private fun generateVrf(input: String): String {
        val encoded = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
        val key = "ysJhV6U27FVIjjuk".toByteArray(Charsets.UTF_8)
        val rc4Bytes = rc4(key, encoded.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(rc4Bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        val shifted = shiftCharcode(b64)
        val b64Shifted = Base64.encodeToString(shifted, Base64.URL_SAFE or Base64.NO_WRAP)
        return rot13(b64Shifted)
    }

    private suspend fun quickGet(url: String): String {
        return app.get(
            url = url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/"
            ),
            interceptor = cfKiller
        ).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("anisuge") ?: mainUrl
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val html = quickGet("$mainUrl/home")
        val soup = Jsoup.parse(html)

        val section = when (request.data) {
            "recent" -> soup.selectFirst("section:has(h2:contains(Recently Updated))") ?: soup.selectFirst("section.pt-4")
            "added" -> soup.selectFirst("section.added")
            "complete" -> soup.selectFirst("section.complete")
            "mostview" -> soup.selectFirst("section.mostview")
            else -> null
        }

        val home = mutableListOf<SearchResponse>()
        section?.select(".item")?.forEach { item ->
            val a = if (item.tagName() == "a") item else item.selectFirst("a[href]")
            val href = a?.attr("href") ?: return@forEach
            val title = item.selectFirst(".name")?.text()?.trim() ?: a?.text()?.trim() ?: "Unknown"
            val img = item.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            })
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("anisuge") ?: mainUrl
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = quickGet("$mainUrl/filter?keyword=$encodedQuery")
        val soup = Jsoup.parse(html)

        val results = mutableListOf<SearchResponse>()
        soup.select(".item")?.forEach { item ->
            val a = if (item.tagName() == "a") item else item.selectFirst("a[href]")
            val href = a?.attr("href") ?: return@forEach
            val title = item.selectFirst(".name")?.text()?.trim() ?: a?.text()?.trim() ?: "Unknown"
            val img = item.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            })
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("anisuge") ?: mainUrl
        val html = quickGet(url)
        val soup = Jsoup.parse(html)

        val dataId = soup.selectFirst(".watch-wrap")?.attr("data-id")
            ?: Regex("""mangaId\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
            ?: return null

        val title = soup.selectFirst(".maindata h1.title")?.text()?.trim()
            ?: soup.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Episode")?.trim()
            ?: "Unknown"

        val poster = soup.selectFirst("meta[property=og:image]")?.attr("content")

        val bannerStyle = soup.selectFirst(".media-bg")?.attr("style")
        val banner = bannerStyle?.let { style ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }

        val plot = soup.selectFirst(".description .full")?.text()?.trim()
            ?: soup.selectFirst(".description")?.text()?.replace("more+", "")?.trim()
            ?: soup.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        var year: Int? = null
        soup.select(".meta div")?.forEach { div ->
            val text = div.text()
            if (text.contains("Premiered:", ignoreCase = true) || text.contains("Aired:", ignoreCase = true)) {
                val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
                if (yearMatch != null) {
                    year = yearMatch.groupValues[1].toIntOrNull()
                }
            }
        }

        val genres = soup.select(".meta a[href*='/genre/'], .data a[href*='/genre/']")?.map { it.text().trim() } ?: emptyList()

        val vrf = generateVrf(dataId)
        val epsResponseText = app.get(
            url = "$mainUrl/ajax/episode/list/$dataId?vrf=$vrf",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            ),
            interceptor = cfKiller
        ).text

        val epsJson = parseJson<EpsResponse>(epsResponseText)
        val epsHtml = epsJson.result ?: return null
        val epsSoup = Jsoup.parse(epsHtml)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        epsSoup.select("a[data-ids]")?.forEach { epLink ->
            val epNum = epLink.text().trim().toIntOrNull()
                ?: epLink.attr("data-slug").toIntOrNull()
                ?: epLink.attr("data-num").filter { it.isDigit() }.toIntOrNull()
                ?: 1
            val epTitle = epLink.attr("data-num")?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
            val dataIds = epLink.attr("data-ids") ?: return@forEach
            // episodes without mapper coords fall back to the legacy server path
            val malId = epLink.attr("data-mal").trim()
            val slug = epLink.attr("data-slug").takeIf { it.isNotBlank() } ?: epNum.toString()
            val timestamp = epLink.attr("data-timestamp").trim()
            val hasSub = epLink.attr("data-sub") == "1"
            val hasDub = epLink.attr("data-dub") == "1"

            if (hasSub) {
                subEpisodes.add(newEpisode("$mainUrl|$dataId|$epNum|$dataIds|$malId|$slug|$timestamp|sub") {
                    this.episode = epNum
                    this.name = epTitle
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("$mainUrl|$dataId|$epNum|$dataIds|$malId|$slug|$timestamp|dub") {
                    this.episode = epNum
                    this.name = epTitle
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // episode data: baseUrl|animeId|epNum|dataIds|malId|slug|timestamp|sub|dub
    // dataIds feeds the legacy server flow, mal/slug/timestamp the mapper api
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        if (!data.startsWith("https://")) return@coroutineScope false
        val parts = data.split("|")
        if (parts.size < 5) return@coroutineScope false
        val baseUrl = parts[0]
        val epNum = parts[2]
        val dataIds = parts[3]
        val malId = parts.getOrNull(4)
        val slug = parts.getOrNull(5) ?: epNum
        val timestamp = parts.getOrNull(6)
        val selectedType = parts.last()

        var anyLoaded = false

        // legacy native servers, currently empty on the site but kept so
        // sources reappear instantly if it restores them
        try {
            if (loadLegacyServers(baseUrl, dataIds, selectedType, subtitleCallback, callback)) {
                anyLoaded = true
            }
        } catch (e: Exception) {
            Log.d("AniSuge", "legacy server path failed: ${e.message}")
        }

        if (!malId.isNullOrBlank() && !timestamp.isNullOrBlank()) {
            try {
                if (loadMapperSources(baseUrl, malId, slug, timestamp, selectedType, subtitleCallback, callback)) {
                    anyLoaded = true
                }
            } catch (e: Exception) {
                Log.d("AniSuge", "mapper path failed: ${e.message}")
            }
        }

        anyLoaded
    }

    private suspend fun loadLegacyServers(
        baseUrl: String,
        dataIds: String,
        selectedType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        // '+' in the base64 blob must be url-encoded or the api mangles it
        val encodedIds = URLEncoder.encode(dataIds, "UTF-8")
        val serverListResponseText = app.get(
            url = "$baseUrl/ajax/server/list?servers=$encodedIds",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "$baseUrl/watch/",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            interceptor = cfKiller
        ).text

        val serverListJson = parseJson<EpsResponse>(serverListResponseText)
        val serverListHtml = serverListJson.result ?: return@coroutineScope false
        val serverListSoup = Jsoup.parse(serverListHtml)

        val serverTypes = serverListSoup.select(".server-type")
        val serversToLoad = mutableListOf<Pair<String, String>>()

        for (st in serverTypes) {
            val typeAttr = st.attr("data-type")
            val isMatch = if (selectedType == "sub") {
                typeAttr == "sub" || typeAttr == "hsub" || typeAttr == "h-sub" || typeAttr == "raw"
            } else {
                typeAttr == "dub" || typeAttr == "adub" || typeAttr == "a-dub"
            }
            if (!isMatch) continue

            for (s in st.select(".server")) {
                val linkId = s.attr("data-link-id") ?: continue
                val serverName = s.selectFirst("span")?.text()?.trim() ?: "Unknown Server"
                serversToLoad.add(Pair(serverName, linkId))
            }
        }

        if (serversToLoad.isEmpty()) return@coroutineScope false

        val loadedResults = serversToLoad.map { (serverName, linkId) ->
            async {
                var loadedSingle = false
                val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                    loadedSingle = true
                    callback(link)
                }
                try {
                    val serverInfoText = app.get(
                        url = "$baseUrl/ajax/server?get=${URLEncoder.encode(linkId, "UTF-8")}",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to "$baseUrl/watch/",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        interceptor = cfKiller
                    ).text

                    val serverInfoJson = parseJson<ServerInfoResponse>(serverInfoText)
                    val playerUrl = serverInfoJson.result?.url ?: return@async false

                    if (resolveEmbed(playerUrl, baseUrl, serverName, subtitleCallback, wrappedCallback)) {
                        loadedSingle = true
                    }
                } catch (e: Exception) {
                    Log.e("AniSuge", "server $serverName failed: ${e.message}")
                }
                loadedSingle
            }
        }.awaitAll()

        loadedResults.any { it }
    }

    private suspend fun loadMapperSources(
        baseUrl: String,
        malId: String,
        slug: String,
        timestamp: String,
        selectedType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val providers = AniSugeMapper.fetchProviders(malId, slug, timestamp) ?: return false

        var anyLoaded = false
        for ((providerKey, subDub) in providers) {
            val displayName = AniSugeMapper.displayProviderName(providerKey)
            val entry = if (selectedType == "dub") subDub.second else subDub.first
            if (entry == null) continue

            val streamUrl = entry.url
            if (streamUrl != null) {
                var embedUrl: String? = null
                try {
                    val serverInfoText = app.get(
                        url = "$baseUrl/ajax/server?get=${URLEncoder.encode(streamUrl, "UTF-8")}",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to "$baseUrl/watch/",
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text
                    val serverInfoJson = parseJson<ServerInfoResponse>(serverInfoText)
                    embedUrl = serverInfoJson.result?.url
                } catch (_: Exception) {
                    // the site may not wrap this one, use the mapper url directly
                }
                if (embedUrl.isNullOrBlank()) embedUrl = streamUrl

                try {
                    if (resolveEmbed(embedUrl, baseUrl, displayName, subtitleCallback, callback)) {
                        anyLoaded = true
                    }
                } catch (e: Exception) {
                    Log.d("AniSuge", "embed $displayName failed: ${e.message}")
                }
            }

            for ((qualityLabel, paheUrl) in entry.downloads) {
                try {
                    val kwikUrl = PaheDownloadResolver.resolveKwikUrl(paheUrl) ?: continue
                    val resolved = kwikExtractor.resolve(kwikUrl, paheUrl) ?: continue
                    val (playUrl, kwikPage) = resolved
                    val qualityInt = qualityLabel.filter { it.isDigit() }.toIntOrNull()
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $displayName $qualityLabel",
                            playUrl,
                            if (playUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityInt ?: Qualities.Unknown.value
                            this.referer = kwikPage
                            this.headers = mapOf("Referer" to kwikPage)
                        }
                    )
                    anyLoaded = true
                } catch (e: Exception) {
                    Log.d("AniSuge", "download $displayName $qualityLabel failed: ${e.message}")
                }
            }
        }
        return anyLoaded
    }

    private suspend fun resolveEmbed(
        playerUrl: String,
        baseUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (playerUrl.contains("plyr.php#")) {
            val b64 = playerUrl.substringAfter("#").substringBefore("#")
            val decodedUrl = try {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
            if (decodedUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        serverName,
                        "$name $serverName",
                        decodedUrl,
                        if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://gogoanime.me.uk/"
                    }
                )
                return true
            }
        }

        val embedHost = Regex("""https?://([^/]+)""").find(playerUrl)?.groupValues?.get(1) ?: ""
        val isMegaplayClone = embedHost.contains("megaplay") ||
                embedHost.contains("vidwish") ||
                embedHost.contains("vidtube") ||
                embedHost.contains("vidstream") ||
                embedHost.contains("vidplay")

        if (isMegaplayClone) {
            val stream = MegaPlayHelper.resolveStream(playerUrl, "$baseUrl/", name)
            if (stream != null) {
                return MegaPlayHelper.emitLinks(
                    name, "$name $serverName", stream.m3u8, "https://$embedHost/",
                    stream.subtitles, subtitleCallback, callback
                )
            }
            Log.e("AniSuge", "megaplay resolution failed for $serverName")
            return false
        }

        val loaded = try {
            loadExtractor(playerUrl, "$baseUrl/", subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
        if (!loaded) {
            try {
                val resolver = com.lagradost.cloudstream3.network.WebViewResolver(
                    interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                    additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                    script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                    useOkhttp = false,
                    timeout = 30_000L
                )
                val resolved = app.get(playerUrl, referer = "$baseUrl/", interceptor = resolver).url
                when {
                    resolved.contains(".m3u8", ignoreCase = true) -> {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = resolved,
                            referer = playerUrl,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                "Referer" to "$playerUrl/"
                            )
                        ).forEach(callback)
                        return true
                    }
                    resolved.contains(".mp4", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name $serverName",
                                url = resolved,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                quality = getQualityFromName(resolved)
                                this.headers = mapOf("Referer" to playerUrl)
                            }
                        )
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e("AniSuge", "webview fallback failed for $serverName: ${e.message}")
            }
        }
        return loaded
    }

    data class EpsResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null
    )

    data class ServerInfoResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: ServerInfoResult? = null
    )

    data class ServerInfoResult(
        @JsonProperty("url") val url: String? = null
    )
}
