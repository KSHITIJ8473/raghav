package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.google.gson.JsonParser
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
        val doc = app.get("${request.data}?page=$page").document
        val items = doc.select("div.ani.items div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encodedQuery").document
        return doc.select("div.ani.items div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
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
                headers = ajaxHeaders
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
        if (data.startsWith("anikoto|")) {
            val parts = data.split("|", limit = 7)
            val referer = parts.getOrNull(1) ?: mainUrl
            val serverIds = parts.getOrNull(2).orEmpty()
            val audioType = parts.getOrNull(6).orEmpty().ifBlank { "sub" }
            if (serverIds.isBlank()) return false

            val serverListJson = app.get(
                "$mainUrl/ajax/server/list?servers=$serverIds",
                referer = referer,
                headers = ajaxHeaders
            ).text

            val serverList = jsonResultString(serverListJson)
            if (serverList.isBlank()) return false

            val serverDoc = Jsoup.parse(serverList)
            val servers = serverDoc.select("div.type[data-type=$audioType] li[data-link-id]")
                .ifEmpty { serverDoc.select("li[data-link-id]") }

            servers.forEach { li ->
                val linkId = li.attr("data-link-id")
                if (linkId.isBlank()) return@forEach

                runCatching {
                    val serverJson = app.get(
                        "$mainUrl/ajax/server?get=$linkId",
                        referer = referer,
                        headers = ajaxHeaders
                    ).text

                    val embedUrl = runCatching {
                        val obj = JsonParser.parseString(serverJson).asJsonObject
                        obj["result"]?.asJsonObject?.get("url")?.asString
                            ?: obj["result"]?.asString
                            ?: obj["link"]?.asString
                            ?: obj["url"]?.asString
                    }.getOrNull() ?: return@runCatching

                    val finalUrl = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
                    val serverName = li.text().trim().ifBlank { "Server" }

                    when {
                        finalUrl.contains("megaplay.buzz") -> {
                            extractMegaplay(finalUrl, serverName, subtitleCallback, callback)
                        }
                        getHashM3u8(finalUrl) != null -> {
                            val m3u8 = getHashM3u8(finalUrl)!!
                            callback.invoke(
                                newExtractorLink(
                                    name, serverName, m3u8,
                                    type = ExtractorLinkType.M3U8
                                ) { this.referer = finalUrl }
                            )
                        }
                        else -> {
                            loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }
            }
            return true
        }

        // Fallback for direct URLs
        val doc = app.get(data).document
        doc.selectFirst("iframe#iframe-embed, iframe[src]")?.attr("src")?.let {
            loadExtractor(fixUrl(it), data, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun extractMegaplay(
        embedUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedHtml = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text
        }.getOrNull() ?: return

        // Extract data-id from the embed page
        val dataId = Regex("""data-id=["'](\d+)["']""")
            .find(embedHtml)?.groupValues?.get(1) ?: return

        // Call getSources API - this is the key API that returns the m3u8
        val sourcesJson = runCatching {
            app.get(
                "https://megaplay.buzz/getSources?id=$dataId&id=$dataId",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        }.getOrNull() ?: return

        val obj = runCatching {
            JsonParser.parseString(sourcesJson).asJsonObject
        }.getOrNull() ?: return

        // Extract m3u8 URL from sources.file
        val m3u8Url = obj["sources"]?.asJsonObject?.get("file")?.asString ?: return

        // Extract subtitles if available
        obj["tracks"]?.asJsonArray?.forEach { track ->
            runCatching {
                val trackObj = track.asJsonObject
                val file = trackObj["file"]?.asString ?: return@forEach
                val label = trackObj["label"]?.asString ?: "Unknown"
                val kind = trackObj["kind"]?.asString ?: ""
                if (kind == "captions" || kind == "subtitles") {
                    subtitleCallback.invoke(SubtitleFile(label, file))
                }
            }
        }

        // Generate M3U8 links with proper referer
        M3u8Helper.generateM3u8(
            serverName,
            m3u8Url,
            embedUrl
        ).forEach(callback)
    }

    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")

    private fun jsonResultString(json: String): String {
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj["status"]?.asInt != 200) return ""
            obj["result"]?.asString.orEmpty()
        }.getOrDefault("")
    }

    private fun getHashM3u8(url: String): String? {
        return url.substringAfter("#", "")
            .substringBefore("#")
            .takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT))
                }.getOrNull()
            }
            ?.let { proxyPlayerHost(it) }
            ?.takeIf { it.startsWith("http") && it.contains(".m3u8") }
    }

    private fun proxyPlayerHost(url: String): String {
        return url
            .replace("vibeplayer.site", "nanobyte.bigdreamsmalldih.site")
            .replace("vault-01.uwucdn.top", "uwu1.bigdreamsmalldih.site")
            .replace("vault-02.uwucdn.top", "uwu2.bigdreamsmalldih.site")
            .replace("vault-03.uwucdn.top", "uwu3.bigdreamsmalldih.site")
            .replace("vault-04.uwucdn.top", "uwu4.bigdreamsmalldih.site")
            .replace("vault-05.uwucdn.top", "uwu5.bigdreamsmalldih.site")
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
        val type = if (selectFirst(".type, .right")?.text()
                ?.contains("Movie", ignoreCase = true) == true) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val hasDub = selectFirst(".dub, i.dub, .fa-microphone") != null
        val hasSub = selectFirst(".sub, i.sub, .fa-closed-captioning") != null || !hasDub

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster?.let { fixUrl(it) }
            addDubStatus(dubExist = hasDub, subExist = hasSub)
        }
    }
}
