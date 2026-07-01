package com.anikoto

import android.util.Base64
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "AniKoto Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Latest Updated",
        "$mainUrl/most-viewed" to "Most Popular",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/type/movie" to "Movies"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}?page=$page", headers = browserHeaders).document
        val items = doc.select("div.ani.items div.item, div.item .inner").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encodedQuery", headers = browserHeaders).document
        return doc.select("div.ani.items div.item, div.item .inner").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document
        val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name], .title[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1.title")?.text()?.trim()
            ?: return null
        val poster = doc.selectFirst("#w-info .poster img, img[itemprop=image], .poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val description = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis, .synopsis .content")?.text()
        val genres = doc.select("#w-info a[href*='/genre/'], .meta a[href*='/genre/']").map { it.text().trim() }
        val isMovie = doc.selectFirst("#w-info a[href*='/type/movie']") != null ||
            doc.selectFirst("#w-info .bmeta")?.text()?.contains("Type: Movie", ignoreCase = true) == true ||
            doc.selectFirst(".bmeta")?.text()?.contains("Movie", ignoreCase = true) == true
        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        animeId?.let { id ->
            try {
                val json = app.get(
                    "$mainUrl/ajax/episode/list/$id",
                    referer = url,
                    headers = ajaxHeaders(url)
                ).text
                val html = jsonResultString(json)
                if (html.isBlank()) {
                    println("AniKoto: Empty episode list HTML for animeId=$id")
                }
                Jsoup.parse(html).select("a[data-ids]").forEach { el ->
                    val serverIds = el.attr("data-ids")
                    val episodeNumber = el.attr("data-num").toIntOrNull()
                    val hasSub = el.attr("data-sub") == "1"
                    val hasDub = el.attr("data-dub") == "1"
                    if (serverIds.isBlank()) return@forEach

                    val episodeName = el.selectFirst(".d-title")?.text()?.ifBlank { null }
                        ?: el.attr("data-jp").ifBlank { "Episode ${episodeNumber ?: ""}" }

                    // Encode: anikoto|referer|serverIds|audioType
                    if (hasSub || !hasDub) {
                        subEpisodes.add(newEpisode("anikoto|$url|$serverIds|sub") {
                            this.episode = episodeNumber
                            this.name = episodeName
                        })
                    }
                    if (hasDub) {
                        dubEpisodes.add(newEpisode("anikoto|$url|$serverIds|dub") {
                            this.episode = episodeNumber
                            this.name = episodeName
                        })
                    }
                }
            } catch (e: Exception) {
                println("AniKoto: Failed to load episodes for animeId=$id - ${e.message}")
            }
        }

        // Fallback: try direct episode links from page
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            doc.select("a[href*='/ep-']").mapIndexed { i, el ->
                subEpisodes.add(newEpisode(fixUrl(el.attr("href"))) {
                    this.episode = i + 1
                    this.name = el.text().ifBlank { "Episode ${i + 1}" }
                })
            }
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
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
        if (data.startsWith("anikoto|")) {
            // Format: anikoto|referer|serverIds|audioType
            val parts = data.split("|", limit = 4)
            if (parts.size < 4) return false
            val referer = parts[1]
            val serverIds = parts[2]
            val audioType = parts[3].ifBlank { "sub" }
            if (serverIds.isBlank()) return false

            println("AniKoto: loadLinks referer=$referer type=$audioType ids=${serverIds.take(30)}...")

            // 1. Fetch server list using the original serverIds (no refresh needed)
            val serverListJson = try {
                app.get(
                    "$mainUrl/ajax/server/list?servers=$serverIds",
                    referer = referer,
                    headers = ajaxHeaders(referer)
                ).text
            } catch (e: Exception) {
                println("AniKoto: Failed to fetch server list - ${e.message}")
                return false
            }

            val serverListHtml = jsonResultString(serverListJson)
            if (serverListHtml.isBlank()) {
                println("AniKoto: Empty server list HTML")
                return false
            }

            val serverDoc = Jsoup.parse(serverListHtml)

            // 2. Select servers by audio type
            // Site has 3 types: sub, hsub (hardsub), dub
            // For "sub" audio: use sub + hsub sections
            // For "dub" audio: use dub section
            val typeSelectors = if (audioType == "dub") {
                listOf("div.type[data-type=dub]")
            } else {
                listOf("div.type[data-type=sub]", "div.type[data-type=hsub]")
            }

            val preferredServers = typeSelectors.flatMap { sel ->
                serverDoc.select("$sel li[data-link-id]")
            }.ifEmpty {
                println("AniKoto: No servers found for type=$audioType, trying all")
                serverDoc.select("li[data-link-id]")
            }

            val linkIds = preferredServers.map { it.attr("data-link-id") }
                .filter { it.isNotBlank() }
                .distinct()

            println("AniKoto: Found ${linkIds.size} servers for type=$audioType")

            if (linkIds.isEmpty()) return false

            // 3. For each server, fetch embed URL and resolve
            var found = false
            for (linkId in linkIds) {
                try {
                    val serverJson = app.get(
                        "$mainUrl/ajax/server?get=$linkId",
                        referer = referer,
                        headers = ajaxHeaders(referer)
                    ).text

                    val embedUrl = jsonResultUrl(serverJson)
                    if (embedUrl.isNullOrBlank()) {
                        println("AniKoto: No embed URL for linkId=${linkId.take(20)}...")
                        continue
                    }

                    println("AniKoto: Resolving embed: ${embedUrl.take(60)}...")
                    if (loadAnikotoLink(embedUrl, referer, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (e: Exception) {
                    println("AniKoto: Server failed - ${e.message}")
                }
            }
            return found
        }

        // Direct URL fallback
        return try {
            val doc = app.get(data, headers = browserHeaders).document
            var found = false
            doc.selectFirst("iframe#iframe-embed, iframe[src]")?.attr("src")?.let {
                found = loadAnikotoLink(fixUrl(it), data, subtitleCallback, callback) || found
            }
            doc.select("li.nav-item a[data-src], ul.nav li a[data-id]").forEach { el ->
                val src = el.attr("data-src").ifBlank { el.attr("data-id") }
                if (src.isNotBlank()) {
                    found = loadAnikotoLink(fixUrl(src), data, subtitleCallback, callback) || found
                }
            }
            found
        } catch (e: Exception) {
            println("AniKoto: Direct URL fallback failed - ${e.message}")
            false
        }
    }

    private fun jsonResultString(json: String): String {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.get("status")?.asInt != 200) ""
            else obj.get("result")?.asString.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun jsonResultUrl(json: String): String? {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.get("status")?.asInt != 200) null
            else obj.get("result")?.asJsonObject?.get("url")?.asString
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadAnikotoLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }

        var found = false
        val trackCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback.invoke(link)
        }

        // Check for hash-encoded m3u8
        getHashM3u8(normalizedUrl)?.let { m3u8 ->
            trackCallback.invoke(
                newExtractorLink(name, "AniKoto M3U8", m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = normalizedUrl
                    this.headers = mapOf("Referer" to normalizedUrl, "Origin" to "https://mewcdn.online")
                }
            )
            return true
        }

        val domain = Regex("""https?://([^/]+)""").find(normalizedUrl)?.groupValues?.get(1) ?: ""
        when {
            domain.contains("megaplay", ignoreCase = true) ||
            domain.contains("vidwish", ignoreCase = true) ||
            domain.contains("vidtube", ignoreCase = true) -> {
                val host = "https://$domain"
                val serverName = when {
                    domain.contains("megaplay", ignoreCase = true) -> "MegaPlay"
                    domain.contains("vidwish", ignoreCase = true) -> "Vidwish"
                    else -> "Vidtube"
                }
                MegaPlay.extractMegaPlayUrl(
                    normalizedUrl, referer, host, serverName,
                    subtitleCallback, trackCallback
                )
            }
            else -> {
                try {
                    loadExtractor(normalizedUrl, referer, subtitleCallback, trackCallback)
                } catch (e: Exception) {
                    println("AniKoto: loadExtractor failed for $domain - ${e.message}")
                }
            }
        }
        return found
    }

    private fun getHashM3u8(url: String): String? {
        val encoded = url.substringAfter("#", "")
            .substringBefore("#")
            .takeIf { it.isNotBlank() } ?: return null
        val decoded = try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            null
        } ?: return null
        return proxyPlayerHost(decoded).takeIf { it.startsWith("http") && it.contains(".m3u8") }
    }

    private fun proxyPlayerHost(url: String): String {
        return url
            .replace("vibeplayer.site", "nanobyte.bigdreamsmalldih.site")
            .replace("vault-01.uwucdn.top", "uwu1.bigdreamsmalldih.site")
            .replace("vault-02.uwucdn.top", "uwu2.bigdreamsmalldih.site")
            .replace("vault-03.uwucdn.top", "uwu3.bigdreamsmalldih.site")
            .replace("vault-04.uwucdn.top", "uwu4.bigdreamsmalldih.site")
            .replace("vault-05.uwucdn.top", "uwu5.bigdreamsmalldih.site")
            .replace("vault-06.uwucdn.top", "uwu6.bigdreamsmalldih.site")
            .replace("vault-07.uwucdn.top", "uwu7.bigdreamsmalldih.site")
            .replace("vault-08.uwucdn.top", "uwu8.bigdreamsmalldih.site")
            .replace("vault-09.uwucdn.top", "uwu9.bigdreamsmalldih.site")
            .replace("vault-10.uwucdn.top", "uwu10.bigdreamsmalldih.site")
            .replace("vault-11.uwucdn.top", "uwu11.bigdreamsmalldih.site")
            .replace("vault-12.uwucdn.top", "uwu12.bigdreamsmalldih.site")
            .replace("vault-13.uwucdn.top", "uwu13.bigdreamsmalldih.site")
            .replace("vault-14.uwucdn.top", "uwu14.bigdreamsmalldih.site")
            .replace("vault-15.uwucdn.top", "uwu15.bigdreamsmalldih.site")
            .replace("vault-16.uwucdn.top", "uwu16.bigdreamsmalldih.site")
            .replace("vault-99.uwucdn.top", "uwu17.bigdreamsmalldih.site")
            .replace("vault-10.owocdn.top", "10.bigdreamsmalldih.site")
            .replace("vault-11.owocdn.top", "11.bigdreamsmalldih.site")
            .replace("vault-12.owocdn.top", "12.bigdreamsmalldih.site")
            .replace("vault-13.owocdn.top", "13.bigdreamsmalldih.site")
            .replace("vault-14.owocdn.top", "14.bigdreamsmalldih.site")
            .replace("vault-15.owocdn.top", "15.bigdreamsmalldih.site")
            .replace("vault-16.owocdn.top", "16.bigdreamsmalldih.site")
            .replace("vault-99.owocdn.top", "99.bigdreamsmalldih.site")
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElement = selectFirst("a.name.d-title") ?: selectFirst("a[title]") ?: selectFirst("a[href*='/watch/']") ?: return null
        val href = titleElement.attr("href").ifBlank {
            selectFirst("div.poster a, a")?.attr("href").orEmpty()
        }
        val title = titleElement.text().trim().ifBlank {
            titleElement.attr("title").trim()
        }
        if (href.isBlank() || title.isBlank()) return null

        val poster = selectFirst("div.poster img, img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val type = if (selectFirst(".type, .right")?.text()?.contains("Movie", ignoreCase = true) == true) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val metaText = select(".meta, .info, .type, .right").text()
        val hasDub = selectFirst(".dub, i.dub, .fa-microphone") != null ||
            metaText.contains("Dub", ignoreCase = true)
        val hasSub = selectFirst(".sub, i.sub, .fa-closed-captioning") != null ||
            metaText.contains("Sub", ignoreCase = true) ||
            !hasDub

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster?.let { fixUrl(it) }
            addDubStatus(dubExist = hasDub, subExist = hasSub)
        }
    }
}
