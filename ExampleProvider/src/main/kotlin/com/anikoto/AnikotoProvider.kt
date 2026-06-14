package com.anikoto

import android.util.Base64
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.cz"
    override var name = "Anikoto"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Latest Updated",
        "$mainUrl/most-viewed" to "Most Popular",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/type/movie" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}?page=$page", headers = browserHeaders).document
        val items = doc.select("div.ani.items div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encodedQuery", headers = browserHeaders).document
        return doc.select("div.ani.items div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document
        val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("#w-info .poster img, img[itemprop=image]")?.attr("src")
        val description = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis")?.text()
        val genres = doc.select("#w-info a[href*='/genre/']").map { it.text().trim() }
        val isMovie = doc.selectFirst("#w-info a[href*='/type/movie']") != null ||
            doc.selectFirst("#w-info .bmeta")?.text()?.contains("Type: Movie", ignoreCase = true) == true
        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        animeId?.let { id ->
            val json = app.get(
                "$mainUrl/ajax/episode/list/$id",
                referer = url,
                headers = ajaxHeaders(url)
            ).text
            val html = jsonResultString(json)
            Jsoup.parse(html).select("a[data-ids]").forEach { el ->
                val serverIds = el.attr("data-ids")
                val episodeNumber = el.attr("data-num").toIntOrNull()
                val slug = el.attr("data-slug")
                val malId = el.attr("data-mal")
                val timestamp = el.attr("data-timestamp")
                val hasSub = el.attr("data-sub") == "1"
                val hasDub = el.attr("data-dub") == "1"
                if (serverIds.isBlank() || slug.isBlank()) return@forEach

                val episodeName = el.attr("title").ifBlank { "Episode ${episodeNumber ?: slug}" }
                if (hasSub || !hasDub) {
                    subEpisodes.add(newEpisode("anikoto|$url|$serverIds|$malId|$slug|$timestamp|sub") {
                        this.episode = episodeNumber
                        this.name = episodeName
                    })
                }
                if (hasDub) {
                    dubEpisodes.add(newEpisode("anikoto|$url|$serverIds|$malId|$slug|$timestamp|dub") {
                        this.episode = episodeNumber
                        this.name = episodeName
                    })
                }
            }
        }

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            doc.select("a[href*='/ep-']").mapIndexed { i, el ->
                subEpisodes.add(newEpisode(fixUrl(el.attr("href"))) {
                    this.episode = i + 1
                    this.name = el.text().ifBlank { "Episode ${i + 1}" }
                })
            }
            if (subEpisodes.isEmpty()) {
                val episodeNumber = Regex("""/ep-(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (episodeNumber != null) {
                    subEpisodes.add(newEpisode(url) {
                        this.episode = episodeNumber
                        this.name = "Episode $episodeNumber"
                    })
                }
            }
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
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
        app.get(mainUrl, headers = browserHeaders)

        if (data.startsWith("anikoto|")) {
            val parts = data.split("|", limit = 7)
            val referer = parts.getOrNull(1) ?: mainUrl
            val serverIds = parts.getOrNull(2).orEmpty()
            val malId = parts.getOrNull(3).orEmpty()
            val slug = parts.getOrNull(4).orEmpty()
            val timestamp = parts.getOrNull(5).orEmpty()
            val audioType = parts.getOrNull(6).orEmpty().ifBlank { "sub" }
            if (serverIds.isBlank()) return false

            val episodeMeta = if (malId.isNotBlank() && slug.isNotBlank() && timestamp.isNotBlank()) {
                EpisodeMeta(malId, slug, timestamp)
            } else {
                getEpisodeMeta(referer, serverIds)
            }

            val freshServerIds = refreshServerIds(referer, slug, serverIds)
            val activeServerIds = freshServerIds ?: serverIds

            val serverListJson = app.get(
                "$mainUrl/ajax/server/list?servers=$activeServerIds",
                referer = referer,
                headers = ajaxHeaders(referer)
            ).text

            val serverListHtml = jsonResultString(serverListJson)
            if (serverListHtml.isBlank()) return false

            val serverDoc = Jsoup.parse(serverListHtml)
            val preferredServers = serverDoc.select("div.type[data-type=$audioType] li[data-link-id]")
                .ifEmpty { serverDoc.select("li[data-link-id]") }

            val linkIds = (
                preferredServers.map { it.attr("data-link-id") } +
                    getMappedServerIds(episodeMeta, audioType)
                ).filter { it.isNotBlank() }.distinct()

            var found = false
            for (linkId in linkIds) {
                if (linkId.startsWith("http", ignoreCase = true)) {
                    if (loadAnikotoLink(linkId, referer, subtitleCallback, callback)) {
                        found = true
                    }
                    continue
                }

                val serverJson = app.get(
                    "$mainUrl/ajax/server?get=$linkId",
                    referer = referer,
                    headers = ajaxHeaders(referer)
                ).text

                val embedUrl = jsonResultUrl(serverJson) ?: continue
                if (loadAnikotoLink(embedUrl, referer, subtitleCallback, callback)) {
                    found = true
                }
            }
            return found
        }

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
        return found
    }

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer,
    )

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

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

    private suspend fun refreshServerIds(referer: String, slug: String, fallback: String): String? {
        if (slug.isBlank()) return null
        return try {
            val animeId = app.get(referer, headers = browserHeaders).document
                .selectFirst("#watch-main")
                ?.attr("data-id")
                .orEmpty()
            if (animeId.isBlank()) null
            else {
                val json = app.get(
                    "$mainUrl/ajax/episode/list/$animeId",
                    referer = referer,
                    headers = ajaxHeaders(referer)
                ).text
                val html = jsonResultString(json)
                val episode = Jsoup.parse(html).selectFirst("a[data-slug=\"$slug\"]")
                episode?.attr("data-ids")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        } ?: fallback.takeIf { it.isNotBlank() }
    }

    private suspend fun getEpisodeMeta(referer: String, serverIds: String): EpisodeMeta? {
        return try {
            val animeDoc = app.get(referer, headers = browserHeaders).document
            val animeId = animeDoc.selectFirst("#watch-main")?.attr("data-id").orEmpty()
            if (animeId.isBlank()) null
            else {
                val json = app.get(
                    "$mainUrl/ajax/episode/list/$animeId",
                    referer = referer,
                    headers = ajaxHeaders(referer)
                ).text
                val html = jsonResultString(json)
                val episode = Jsoup.parse(html).selectFirst("a[data-ids=\"$serverIds\"]")
                if (episode == null) null
                else {
                    EpisodeMeta(
                        episode.attr("data-mal"),
                        episode.attr("data-slug"),
                        episode.attr("data-timestamp")
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getMappedServerIds(meta: EpisodeMeta?, audioType: String): List<String> {
        val malId = meta?.malId.orEmpty()
        val slug = meta?.slug.orEmpty()
        val timestamp = meta?.timestamp.orEmpty()
        if (malId.isBlank() || slug.isBlank() || timestamp.isBlank()) return emptyList()

        return try {
            val json = app.get("https://mapper.nekostream.site/api/mal/$malId/$slug/$timestamp").text
            val obj = JsonParser.parseString(json).asJsonObject
            obj.entrySet().flatMap { (_, value) ->
                if (!value.isJsonObject) emptyList()
                else {
                    val source = value.asJsonObject
                    listOfNotNull(source[audioType]?.asJsonObject?.get("url")?.asString)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class EpisodeMeta(
        val malId: String,
        val slug: String,
        val timestamp: String,
    )

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

        getHashM3u8(normalizedUrl)?.let { m3u8 ->
            trackCallback.invoke(
                newExtractorLink(name, "Anikoto M3U8", m3u8, type = ExtractorLinkType.M3U8) {
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
                val serverName = if (domain.contains("megaplay", ignoreCase = true)) "MegaPlay"
                                 else if (domain.contains("vidwish", ignoreCase = true)) "Vidwish"
                                 else "Vidtube"
                MegaPlay.extractMegaPlayUrl(
                    normalizedUrl, referer, host, serverName,
                    subtitleCallback, trackCallback
                )
            }
            else -> {
                loadExtractor(normalizedUrl, referer, subtitleCallback, trackCallback)
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
        val titleElement = selectFirst("a.name.d-title") ?: selectFirst("a[title]") ?: return null
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
