package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.api.Log
import org.jsoup.Jsoup
import java.net.URLEncoder

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

    // VRF Hashing helpers
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
            val epNum = epLink.text().toIntOrNull() ?: epLink.attr("data-slug").toIntOrNull() ?: 1
            val epTitle = epLink.attr("data-num")?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
            val dataIds = epLink.attr("data-ids") ?: return@forEach
            val hasSub = epLink.attr("data-sub") == "1"
            val hasDub = epLink.attr("data-dub") == "1"

            if (hasSub) {
                subEpisodes.add(newEpisode("$mainUrl|$dataId|$epNum|$dataIds|sub") {
                    this.episode = epNum
                    this.name = epTitle
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("$mainUrl|$dataId|$epNum|$dataIds|dub") {
                    this.episode = epNum
                    this.name = epTitle
                })
            }
        }

        val tvType = if (soup.selectFirst(".status .type")?.text()?.contains("MOVIE", ignoreCase = true) == true) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            this.tags = genres
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
        if (!data.startsWith("https://")) return false
        val parts = data.split("|")
        if (parts.size < 5) return false
        val baseUrl = parts[0]
        val animeId = parts[1]
        val epNum = parts[2]
        val dataIds = parts[3]
        val selectedType = parts[4] // "sub" or "dub"

        val serverListResponseText = app.get(
            url = "$baseUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "$baseUrl/watch/",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            interceptor = cfKiller
        ).text

        val serverListJson = parseJson<EpsResponse>(serverListResponseText)
        val serverListHtml = serverListJson.result ?: return false
        val serverListSoup = Jsoup.parse(serverListHtml)

        val serverTypes = serverListSoup.select(".server-type")
        var foundAny = false

        for (st in serverTypes) {
            val typeAttr = st.attr("data-type")
            val isMatch = if (selectedType == "sub") {
                typeAttr == "sub" || typeAttr == "hsub"
            } else {
                typeAttr == "dub"
            }

            if (!isMatch) continue

            val servers = st.select(".server")
            for (s in servers) {
                val linkId = s.attr("data-link-id") ?: continue
                val serverName = s.selectFirst("span")?.text()?.trim() ?: "Unknown Server"

                try {
                    val serverInfoText = app.get(
                        url = "$baseUrl/ajax/server?get=$linkId",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to "$baseUrl/watch/",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        interceptor = cfKiller
                    ).text

                    val serverInfoJson = parseJson<ServerInfoResponse>(serverInfoText)
                    val playerUrl = serverInfoJson.result?.url ?: continue

                    val parsedUrl = java.net.URI(playerUrl)
                    val embedBase = "${parsedUrl.scheme}://${parsedUrl.host}"

                    val playerPageHtml = app.get(
                        url = playerUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to "$baseUrl/"
                        )
                    ).text

                    val playerPageSoup = Jsoup.parse(playerPageHtml)
                    val playerId = playerPageSoup.selectFirst("#megaplay-player")?.attr("data-id")
                        ?: Regex("""data-id=["'](\d+)""").find(playerPageHtml)?.groupValues?.get(1)
                        ?: Regex("""/stream/s-\d+/(\d+)""").find(playerUrl)?.groupValues?.get(1)
                        ?: continue

                    val sourcesText = app.get(
                        url = "$embedBase/stream/getSources?id=$playerId&type=$selectedType",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to playerUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to embedBase
                        )
                    ).text

                    val sourcesJson = parseJson<SourcesResponse>(sourcesText)
                    val m3u8Url = sourcesJson.sources?.file

                    if (!m3u8Url.isNullOrEmpty()) {
                        M3u8Helper.generateM3u8(
                            source = "$name - $serverName",
                            streamUrl = m3u8Url,
                            referer = embedBase,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                "Referer" to "$embedBase/"
                            )
                        ).forEach(callback)

                        foundAny = true
                    }

                    sourcesJson.tracks?.forEach { track ->
                        val file = track.file ?: return@forEach
                        if (track.kind == "captions" || track.kind == "subtitles") {
                            subtitleCallback(
                                newSubtitleFile(track.label ?: "Subtitle", file) {
                                    this.headers = mapOf("Referer" to "$embedBase/")
                                }
                            )
                        }
                    }

                } catch (e: Exception) {
                    Log.e("AniSuge", "Failed loading links from server $serverName: ${e.message}")
                }
            }
        }

        return foundAny
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

    data class SourcesResponse(
        @JsonProperty("sources") val sources: SourcesFile? = null,
        @JsonProperty("tracks") val tracks: List<SourcesTrack>? = null
    )

    data class SourcesFile(
        @JsonProperty("file") val file: String? = null
    )

    data class SourcesTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
