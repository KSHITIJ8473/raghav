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
import com.lagradost.cloudstream3.newSubtitleFile
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URL
import java.net.URLDecoder

class Anikai : MainAPI() {
    override var mainUrl = "https://www3.anikai.cc"
    override var name = "Anikai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest Updates",
        "New Releases" to "New Releases",
        "Top Airing" to "Top Airing",
        "Completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home").document
        val home = mutableListOf<SearchResponse>()
        val category = request.data

        if (category == "latest-updates") {
            val items = doc.select(".r-update .aitem, .load-widget .aitem")
            for (item in items) {
                val aTag = item.selectFirst("a.poster") ?: continue
                var href = aTag.attr("href")
                href = href.replace(Regex("/ep-\\d+$"), "")
                val fixHref = fixUrl(href)
                val title = item.selectFirst(".title")?.text()?.trim() ?: continue
                val img = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

                home.add(newAnimeSearchResponse(title, fixHref, TvType.Anime) {
                    this.posterUrl = img
                    addDubStatus(dubExist = true, subExist = true)
                })
            }
        } else {
            // Find section matching the category name (e.g. New Releases, Top Airing, Completed)
            val section = doc.select("div.inner, section.swiper-slide").firstOrNull { sec ->
                sec.select("span.stitle").text().contains(category, ignoreCase = true)
            }
            if (section != null) {
                val items = section.select(".aitem")
                for (item in items) {
                    var href = item.attr("href").ifEmpty { item.selectFirst("a")?.attr("href") } ?: continue
                    href = href.replace(Regex("/ep-\\d+$"), "")
                    val fixHref = fixUrl(href)
                    val title = item.selectFirst(".title")?.text()?.trim() ?: continue
                    val img = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

                    home.add(newAnimeSearchResponse(title, fixHref, TvType.Anime) {
                        this.posterUrl = img
                        addDubStatus(dubExist = true, subExist = true)
                    })
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browser?keyword=${java.net.URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        val items = doc.select(".aitem")
        for (item in items) {
            val aTag = item.selectFirst("a.poster") ?: continue
            var href = aTag.attr("href")
            href = href.replace(Regex("/ep-\\d+$"), "")
            val fixHref = fixUrl(href)
            val title = item.selectFirst(".title")?.text()?.trim() ?: continue
            val img = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

            results.add(newAnimeSearchResponse(title, fixHref, TvType.Anime) {
                this.posterUrl = img
                addDubStatus(dubExist = true, subExist = true)
            })
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.title")?.text()?.trim() ?: return null
        val jpTitle = doc.selectFirst("h1.title")?.attr("data-jp")
        val posterUrl = doc.selectFirst(".poster img")?.attr("src") ?: doc.selectFirst("img[itemprop=image]")?.attr("src")
        val backgroundUrl = doc.selectFirst(".watch-section-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }
        val plot = doc.selectFirst(".desc.text-expand")?.text()?.trim() ?: doc.selectFirst(".desc")?.text()?.trim()
        val year = doc.selectFirst("a[href*=year]")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("a[href*=/genres/]").map { it.text().replace(Regex("^,\\s*"), "").trim() }

        val statusStr = doc.select("div").firstOrNull { it.text().contains("Status:") }?.selectFirst("span")?.text()?.trim()
        val showStatus = when (statusStr?.lowercase()) {
            "currently airing" -> ShowStatus.Ongoing
            "finished airing", "completed" -> ShowStatus.Completed
            else -> null
        }

        val typeStr = doc.select("div").firstOrNull { it.text().contains("Type:") }?.selectFirst("span")?.text()?.trim() ?: ""
        val tvType = when (typeStr.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        val epLinks = doc.select(".eplist ul.range li a")
        for (ep in epLinks) {
            val epHref = ep.attr("href")
            if (epHref.isEmpty()) continue
            val epNum = ep.attr("data-num").toIntOrNull() ?: continue
            val name = ep.text().trim().ifEmpty { "Episode $epNum" }

            val hasSub = ep.attr("data-sub") == "1" || ep.attr("data-hsub") == "1"
            val hasDub = ep.attr("data-dub") == "1"

            if (hasSub) {
                subEpisodes.add(newEpisode("sub|${fixUrl(epHref)}") {
                    this.name = name
                    this.episode = epNum
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("dub|${fixUrl(epHref)}") {
                    this.name = name
                    this.episode = epNum
                })
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
        val parts = data.split("|")
        if (parts.size < 2) return false

        val dubOrSub = parts[0]
        val watchUrl = parts[1]

        val doc = app.get(watchUrl).document
        val targetGroups = if (dubOrSub == "dub") listOf("dub") else listOf("sub", "hsub")
        val serverItems = doc.select(".server-items").filter { it.attr("data-id") in targetGroups }
        val servers = serverItems.flatMap { it.select("span.server-video") }

        var foundAnySources = false

        for (server in servers) {
            val embedUrl = server.attr("data-video")
            if (embedUrl.isEmpty()) continue
            val serverName = server.text().trim()
            val isDub = server.parents().firstOrNull { it.hasClass("server-items") }?.attr("data-id") == "dub"

            // Try to extract subtitle parameters from the embed URL
            try {
                val urlObj = URL(embedUrl)
                val query = urlObj.query ?: ""
                val subParam = Regex("""(?:sub|caption_1|c1_file)=([^&]+)""").find(query)?.groupValues?.get(1)
                if (subParam != null) {
                    val decodedSub = URLDecoder.decode(subParam, "utf-8")
                    val label = Regex("""(?:sub_1|c1_label)=([^&]+)""").find(query)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "utf-8") } ?: "English"
                    subtitleCallback.invoke(
                        newSubtitleFile(label, decodedSub)
                    )
                }
            } catch (_: Exception) {}

            try {
                when {
                    embedUrl.contains("vivibebe.site") || embedUrl.contains("bibiemb.xyz") -> {
                        val embedHtml = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                        val m3u8Url = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(embedHtml)?.groupValues?.get(1)
                        if (m3u8Url != null) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$serverName (${if (isDub) "Dub" else "Sub"})",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf("Referer" to embedUrl)
                                }
                            )
                            foundAnySources = true
                        }
                    }
                    embedUrl.contains("otakuhg.site") || embedUrl.contains("otakuvid.online") || embedUrl.contains("earnvids.com") -> {
                        val embedHtml = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                        val unpacked = JsPacker.parseAndUnpack(embedHtml)
                        if (unpacked != null) {
                            val m3u8Url = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(unpacked)?.groupValues?.get(1)
                            if (m3u8Url != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverName (${if (isDub) "Dub" else "Sub"})",
                                        url = m3u8Url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.headers = mapOf("Referer" to embedUrl)
                                    }
                                )
                                foundAnySources = true
                            }
                        }
                    }
                    embedUrl.contains("playmogo.com") -> {
                        loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                        foundAnySources = true
                    }
                    else -> {
                        val loaded = loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                        if (loaded) {
                            foundAnySources = true
                        } else {
                            // Fallback direct scan on embed HTML page
                            val embedHtml = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                            val m3u8Url = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(embedHtml)?.groupValues?.get(1)
                            if (m3u8Url != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverName (${if (isDub) "Dub" else "Sub"})",
                                        url = m3u8Url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.headers = mapOf("Referer" to embedUrl)
                                    }
                                )
                                foundAnySources = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return foundAnySources
    }
}

object JsPacker {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun baseN(num: Int, base: Int): String {
        if (num == 0) return CHARS[0].toString()
        var temp = num
        val sb = StringBuilder()
        while (temp > 0) {
            sb.append(CHARS[temp % base])
            temp /= base
        }
        return sb.reverse().toString()
    }

    fun unpack(p: String, a: Int, c: Int, k: List<String>): String {
        var payload = p
        for (i in c - 1 downTo 0) {
            if (i < k.size && k[i].isNotEmpty()) {
                val key = k[i]
                val baseStr = baseN(i, a)
                val regex = Regex("\\b$baseStr\\b")
                payload = payload.replace(regex, key)
            }
        }
        return payload
    }

    fun parseAndUnpack(html: String): String? {
        val startIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
        val actualStart = if (startIdx != -1) startIdx else html.indexOf("function(p,a,c,k,e,d)")
        if (actualStart == -1) return null

        val openBraceIdx = html.indexOf("{", actualStart)
        if (openBraceIdx == -1) return null

        var braceCount = 1
        var j = openBraceIdx + 1
        while (j < html.length && braceCount > 0) {
            if (html[j] == '{') braceCount++
            else if (html[j] == '}') braceCount--
            j++
        }

        val argsStartIdx = html.indexOf("(", j - 1)
        if (argsStartIdx == -1) return null

        var argsParenCount = 1
        var kIdx = argsStartIdx + 1
        while (kIdx < html.length && argsParenCount > 0) {
            if (html[kIdx] == '(') argsParenCount++
            else if (html[kIdx] == ')') argsParenCount--
            kIdx++
        }

        val argsStr = html.substring(argsStartIdx + 1, kIdx - 1).trim()
        if (argsStr.isEmpty()) return null

        val startChar = argsStr.first()
        var payload = ""
        var i = 1
        while (i < argsStr.length) {
            if (argsStr[i] == startChar) {
                var backslashCount = 0
                var m = i - 1
                while (m >= 0 && argsStr[m] == '\\') {
                    backslashCount++
                    m--
                }
                if (backslashCount % 2 == 0) break
            }
            payload += argsStr[i]
            i++
        }

        payload = payload.replace("\\$startChar", startChar.toString()).replace("\\\\", "\\")

        val rest = argsStr.substring(i + 1)
        val restQuoteMatch = Regex("[\"']").find(rest) ?: return null
        val quotePos = restQuoteMatch.range.first
        val restQuoteChar = restQuoteMatch.value

        val ints = Regex("\\b\\d+\\b").findAll(rest.substring(0, quotePos)).map { it.value.toInt() }.toList()
        if (ints.size < 2) return null
        val a = ints[0]
        val c = ints[1]

        var keysStr = ""
        var jj = quotePos + 1
        while (jj < rest.length) {
            if (rest[jj].toString() == restQuoteChar) {
                var backslashCount = 0
                var m = jj - 1
                while (m >= 0 && rest[m] == '\\') {
                    backslashCount++
                    m--
                }
                if (backslashCount % 2 == 0) break
            }
            keysStr += rest[jj]
            jj++
        }

        keysStr = keysStr.replace("\\$restQuoteChar", restQuoteChar).replace("\\\\", "\\")
        val keys = keysStr.split("|")

        return unpack(payload, a, c, keys)
    }
}
