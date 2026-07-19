package com.anikoto

import android.util.Base64
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newSubtitleFile
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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}?page=$page", headers = browserHeaders).document
        val items = doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encodedQuery", headers = browserHeaders).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = try {
            app.get(url, headers = browserHeaders)
        } catch (e: Exception) {
            return null
        }
        val doc = response.document
        val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name], .title[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1.title")?.text()?.trim()
            ?: run {
                return null
            }
        val poster = doc.selectFirst("#w-info .poster img, img[itemprop=image], .poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val description = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis, .synopsis .content")?.text()
        val genres = doc.select("#w-info a[href*='/genre/'], .meta a[href*='/genre/']").map { it.text().trim() }
        val isMovie = doc.selectFirst("#w-info a[href*='/type/movie']") != null ||
            doc.selectFirst(".bmeta")?.text()?.contains("Movie", ignoreCase = true) == true

        // Try multiple selectors for the anime ID — the page may render differently.
        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (animeId != null) {
            try {
                val json = app.get(
                    "$mainUrl/ajax/episode/list/$animeId",
                    referer = url, headers = ajaxHeaders(url)
                ).text
                val html = jsonResultString(json)
                if (html.isBlank()) {
                }
                Jsoup.parse(html).select("a[data-ids]").forEach { el ->
                    val serverIds = el.attr("data-ids")
                    val episodeNumber = el.attr("data-num").toIntOrNull()
                    val hasSub = el.attr("data-sub") == "1"
                    val hasDub = el.attr("data-dub") == "1"
                    if (serverIds.isBlank()) return@forEach

                    // Episode title — try multiple sources, most specific first:
                    // 1. <span class="d-title"> text content (the real English episode title)
                    // 2. <li title="..."> attribute (parent <li> carries the same title)
                    // 3. <span class="d-title" data-jp="..."> attribute (Japanese/placeholder, e.g. "Episode 1")
                    // 4. Fallback: "Episode N"
                    val dTitleSpan = el.selectFirst(".d-title")
                    val episodeName = dTitleSpan?.text()?.trim()?.ifBlank { null }
                        ?: el.parent()?.attr("title")?.trim()?.ifBlank { null }
                        ?: dTitleSpan?.attr("data-jp")?.trim()?.ifBlank { null }
                        ?: "Episode ${episodeNumber ?: ""}"

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
            }
        }

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            doc.select("a[href*='/ep-']").mapIndexed { i, el ->
                subEpisodes.add(newEpisode(fixUrl(el.attr("href"))) {
                    this.episode = i + 1
                    this.name = el.text().ifBlank { "Episode ${i + 1}" }
                })
            }
        }

        val finalType = if (isMovie && dubEpisodes.isNotEmpty()) TvType.Anime else if (isMovie) TvType.AnimeMovie else TvType.Anime
        return newAnimeLoadResponse(title, url, finalType) {
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
        // CloudStream may prepend mainUrl to the data string if it doesn't start
        // with http. Strip it so the anikoto| prefix is detected correctly.
        val cleanData = when {
            data.startsWith("$mainUrl/anikoto|") -> data.removePrefix("$mainUrl/")
            data.startsWith("/anikoto|") -> data.removePrefix("/")
            else -> data
        }

        if (cleanData.startsWith("anikoto|")) {
            val parts = cleanData.split("|", limit = 4)
            if (parts.size < 4) return false
            val referer = parts[1]
            val serverIds = parts[2]
            val audioType = parts[3].ifBlank { "sub" }
            if (serverIds.isBlank()) return false
            return resolveServers(serverIds, referer, audioType, subtitleCallback, callback)
        }

        // Direct URL fallback — episode page was stored directly (AJAX failed during load).
        // The episode page has the same #watch-main data-id as the anime page, so we can
        // retry the AJAX here, find the matching episode by number, and resolve servers.
        return try {
            val doc = app.get(cleanData, headers = browserHeaders).document
            // Try multiple selectors — same as load()
            val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
                ?: doc.selectFirst("[data-id]")?.attr("data-id")
                ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)
            val epNum = Regex("""/ep-(\d+)""").find(cleanData)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (animeId.isNullOrBlank()) {
                return false
            }

            // Retry the AJAX episode list
            val json = app.get(
                "$mainUrl/ajax/episode/list/$animeId",
                referer = data, headers = ajaxHeaders(data)
            ).text
            val html = jsonResultString(json)
            if (html.isBlank()) {
                return false
            }

            // Find the matching episode by data-num
            val epEl = Jsoup.parse(html).select("a[data-ids]").find {
                it.attr("data-num").toIntOrNull() == epNum
            } ?: Jsoup.parse(html).selectFirst("a[data-ids]") ?: run {
                return false
            }

            val serverIds = epEl.attr("data-ids")
            val audioType = if (epEl.attr("data-dub") == "1") "dub" else "sub"
            if (serverIds.isBlank()) return false

            resolveServers(serverIds, data, audioType, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shared server resolution — used by both the anikoto| data branch and the
     * direct URL fallback. Fetches the server list, picks servers by audio type,
     * and resolves each embed URL.
     */
    private suspend fun resolveServers(
        serverIds: String,
        referer: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // serverIds is a base64 blob containing '+', '=', '/' which MUST be
        // URL-encoded. Without encoding, '+' becomes a space server-side and the
        // API returns 500 "Bad request" → empty server list → "no link found".
        val encodedIds = URLEncoder.encode(serverIds, "UTF-8")
        val serverListJson = try {
            app.get("$mainUrl/ajax/server/list?servers=$encodedIds",
                referer = referer, headers = ajaxHeaders(referer)).text
        } catch (e: Exception) {
            return false
        }

        val serverListHtml = jsonResultString(serverListJson)
        if (serverListHtml.isBlank()) {
            return false
        }

        val serverDoc = Jsoup.parse(serverListHtml)

        val typeSelectors = if (audioType == "dub") {
            listOf("div.type[data-type=dub]")
        } else {
            listOf("div.type[data-type=sub]", "div.type[data-type=hsub]")
        }

        val preferredServers = typeSelectors.flatMap { sel ->
            serverDoc.select("$sel li[data-link-id]")
        }.ifEmpty {
            serverDoc.select("li[data-link-id]")
        }

        val linkIds = preferredServers.map { it.attr("data-link-id") }
            .filter { it.isNotBlank() }.distinct()
        if (linkIds.isEmpty()) return false

        var found = false
        for (linkId in linkIds) {
            try {
                val encodedLinkId = URLEncoder.encode(linkId, "UTF-8")
                val serverJson = app.get("$mainUrl/ajax/server?get=$encodedLinkId",
                    referer = referer, headers = ajaxHeaders(referer)).text
                var embedUrl = jsonResultUrl(serverJson)
                if (embedUrl.isNullOrBlank()) continue

                if (audioType == "dub" && embedUrl.contains("/sub")) {
                    embedUrl = embedUrl.replace("/sub", "/dub")
                } else if (audioType == "sub" && embedUrl.contains("/dub")) {
                    embedUrl = embedUrl.replace("/dub", "/sub")
                }

                if (resolveEmbedInline(embedUrl, referer, audioType, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
            }
        }
        return found
    }

    /**
     * Inline embed resolution — no dependency on companion object.
     * Handles megaplay.buzz, vidtube.site, vidwish.live.
     */
    private suspend fun resolveEmbedInline(
        url: String,
        referer: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }

        // Check for hash-encoded m3u8
        getHashM3u8(normalizedUrl)?.let { m3u8 ->
            callback.invoke(
                newExtractorLink("AniKoto", "AniKoto M3U8", m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = normalizedUrl
                    this.headers = mapOf("Referer" to normalizedUrl, "Origin" to "https://mewcdn.online")
                }
            )
            return true
        }

        val domain = Regex("""https?://([^/]+)""").find(normalizedUrl)?.groupValues?.get(1) ?: ""
        val isMegaPlayDomain = domain.contains("megaplay", ignoreCase = true) ||
                               domain.contains("vidwish", ignoreCase = true) ||
                               domain.contains("vidtube", ignoreCase = true)

        return if (isMegaPlayDomain) {
            resolveMegaPlayInline(normalizedUrl, referer, domain, audioType, subtitleCallback, callback)
        } else {
            try {
                loadExtractor(normalizedUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Inline MegaPlay/VidTube/VidWish resolution.
     * Chain: fetch page → extract data-id → fetch getSources → get m3u8
     */
    private suspend fun resolveMegaPlayInline(
        url: String,
        referer: String,
        domain: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = "https://$domain"
        val serverName = when {
            domain.contains("megaplay", ignoreCase = true) -> "MegaPlay"
            domain.contains("vidwish", ignoreCase = true) -> "Vidwish"
            else -> "Vidtube"
        }
        val type = if (url.contains("/dub", ignoreCase = true) || audioType == "dub") "dub" else "sub"

        val pageHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
        )

        val ajaxHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to host,
            "Referer" to url,
        )

        val playbackHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Origin" to host,
            "Referer" to "$host/",
        )

        try {
            // 1. Fetch embed page to get data-id
            val doc = app.get(url, headers = pageHeaders).document
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-id")
                ?: playerEl?.attr("data-realid")
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return false
            if (streamId.isBlank()) return false

            // 2. Fetch getSources
            val sourcesText = app.get("$host/stream/getSources?id=$streamId&type=$type",
                headers = ajaxHeaders, referer = url).text
            val root = JsonParser.parseString(sourcesText).asJsonObject

            // sources can be object or array
            val m3u8 = try {
                val sourcesEl = root.get("sources")
                if (sourcesEl?.isJsonObject == true) {
                    sourcesEl.asJsonObject.get("file")?.asString
                } else if (sourcesEl?.isJsonArray == true && sourcesEl.asJsonArray.size() > 0) {
                    sourcesEl.asJsonArray[0].asJsonObject.get("file")?.asString
                } else null
            } catch (_: Exception) { null }

            if (m3u8.isNullOrBlank()) {
                return false
            }

            // 3. Generate m3u8 links
            val displayType = if (type == "dub") "DUB" else "SUB"
            val generated = M3u8Helper.generateM3u8(
                "AniKoto $serverName $displayType", m3u8, host, headers = playbackHeaders
            )
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "AniKoto",
                        name = "AniKoto $serverName $displayType",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$host/"
                        this.headers = playbackHeaders
                    }
                )
            }

            // 4. Subtitles
            try {
                val tracks = root.getAsJsonArray("tracks")
                if (tracks != null) {
                    for (element in tracks) {
                        val track = element.asJsonObject
                        val kind = track.get("kind")?.asString ?: continue
                        if (kind != "captions" && kind != "subtitles") continue
                        val file = track.get("file")?.asString ?: continue
                        val trackUrl = if (file.startsWith("http")) file else "$host/${file.removePrefix("/")}"
                        val label = track.get("label")?.asString ?: "English"
                        val subHeaders = when {
                            trackUrl.contains("lostproject.club") -> mapOf("Referer" to "https://megaplay.buzz/")
                            trackUrl.contains("nekostream.site") -> mapOf("Referer" to "$host/")
                            else -> playbackHeaders
                        }
                        subtitleCallback.invoke(newSubtitleFile(label, trackUrl) {
                            this.headers = subHeaders
                        })
                    }
                }
            } catch (e: Exception) { e.message?.let { Log.d("Plugin", it) } }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun jsonResultString(json: String): String {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.get("status")?.asInt != 200) ""
            else obj.get("result")?.asString.orEmpty()
        } catch (e: Exception) { "" }
    }

    private fun jsonResultUrl(json: String): String? {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.get("status")?.asInt != 200) null
            else obj.get("result")?.asJsonObject?.get("url")?.asString
        } catch (e: Exception) { null }
    }

    private fun getHashM3u8(url: String): String? {
        val encoded = url.substringAfter("#", "").substringBefore("#").takeIf { it.isNotBlank() } ?: return null
        val decoded = try { String(Base64.decode(encoded, Base64.DEFAULT)) } catch (e: Exception) { null } ?: return null
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
        val href = titleElement.attr("href").ifBlank { selectFirst("div.poster a, a")?.attr("href").orEmpty() }
        val title = titleElement.text().trim().ifBlank { titleElement.attr("title").trim() }
        if (href.isBlank() || title.isBlank()) return null
        val poster = selectFirst("div.poster img, img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val type = if (selectFirst(".type, .right")?.text()?.contains("Movie", ignoreCase = true) == true) TvType.AnimeMovie else TvType.Anime
        val metaText = select(".meta, .info, .type, .right").text()
        val hasDub = selectFirst(".dub, i.dub, .fa-microphone") != null || metaText.contains("Dub", ignoreCase = true)
        val hasSub = selectFirst(".sub, i.sub, .fa-closed-captioning") != null || metaText.contains("Sub", ignoreCase = true) || !hasDub
        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster?.let { fixUrl(it) }
            addDubStatus(dubExist = hasDub, subExist = hasSub)
        }
    }
}
