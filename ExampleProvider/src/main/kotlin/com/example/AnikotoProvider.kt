package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

        val episodes = animeId?.let { id ->
            val json = app.get(
                "$mainUrl/ajax/episode/list/$id",
                referer = url,
                headers = ajaxHeaders
            ).text
            val html = jsonResultString(json)
            Jsoup.parse(html).select("a[data-ids]").mapNotNull { el ->
                val serverIds = el.attr("data-ids")
                val episodeNumber = el.attr("data-num").toIntOrNull()
                val slug = el.attr("data-slug")
                val malId = el.attr("data-mal")
                val timestamp = el.attr("data-timestamp")
                if (serverIds.isBlank() || slug.isBlank()) return@mapNotNull null

                newEpisode("anikoto|$url|$serverIds|$malId|$slug|$timestamp") {
                    this.episode = episodeNumber
                    this.name = el.attr("title").ifBlank { "Episode ${episodeNumber ?: slug}" }
                }
            }
        }.orEmpty().ifEmpty {
            doc.select("a[href*='/ep-']").mapIndexed { i, el ->
                newEpisode(fixUrl(el.attr("href"))) {
                    this.episode = i + 1
                    this.name = el.text().ifBlank { "Episode ${i + 1}" }
                }
            }.ifEmpty {
                val episodeNumber = Regex("""/ep-(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (episodeNumber != null) {
                    listOf(
                        newEpisode(url) {
                            this.episode = episodeNumber
                            this.name = "Episode $episodeNumber"
                        }
                    )
                } else {
                    emptyList()
                }
            }
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("anikoto|")) {
            val parts = data.split("|", limit = 6)
            val referer = parts.getOrNull(1) ?: mainUrl
            val serverIds = parts.getOrNull(2).orEmpty()
            val malId = parts.getOrNull(3).orEmpty()
            val slug = parts.getOrNull(4).orEmpty()
            val timestamp = parts.getOrNull(5).orEmpty()
            if (serverIds.isBlank()) return false
            val episodeMeta = if (malId.isNotBlank() && slug.isNotBlank() && timestamp.isNotBlank()) {
                EpisodeMeta(malId, slug, timestamp)
            } else {
                getEpisodeMeta(referer, serverIds)
            }

            val serverListJson = app.get(
                "$mainUrl/ajax/server/list?servers=$serverIds",
                referer = referer,
                headers = ajaxHeaders
            ).text
            val serverList = jsonResultString(serverListJson)
            val serverDoc = Jsoup.parse(serverList)
            val linkIds = (
                serverDoc.select("li[data-link-id]").map { it.attr("data-link-id") } +
                    getMappedServerIds(episodeMeta)
                ).filter { it.isNotBlank() }.distinct()

            var found = false
            linkIds.forEach { linkId ->
                val serverJson = app.get(
                    "$mainUrl/ajax/server?get=$linkId",
                    referer = referer,
                    headers = ajaxHeaders
                ).text
                val url = jsonResultUrl(serverJson)
                if (!url.isNullOrBlank()) {
                    found = loadAnikotoLink(url, referer, subtitleCallback, callback) || found
                }
            }
            return found
        }

        val doc = app.get(data).document
        doc.selectFirst("iframe#iframe-embed, iframe[src]")?.attr("src")?.let {
            loadAnikotoLink(fixUrl(it), data, subtitleCallback, callback)
        }
        doc.select("li.nav-item a[data-src], ul.nav li a[data-id]").forEach { el ->
            val src = el.attr("data-src").ifBlank { el.attr("data-id") }
            if (src.isNotBlank()) loadAnikotoLink(fixUrl(src), data, subtitleCallback, callback)
        }
        return true
    }

    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")

    private fun jsonResultString(json: String): String {
        val obj = JsonParser.parseString(json).asJsonObject
        if (obj["status"]?.asInt != 200) return ""
        return obj["result"]?.asString.orEmpty()
    }

    private fun jsonResultUrl(json: String): String? {
        val obj = JsonParser.parseString(json).asJsonObject
        if (obj["status"]?.asInt != 200) return null
        return obj["result"]?.asJsonObject?.get("url")?.asString
    }

    private suspend fun getEpisodeMeta(referer: String, serverIds: String): EpisodeMeta? {
        return runCatching {
            val animeDoc = app.get(referer).document
            val animeId = animeDoc.selectFirst("#watch-main")?.attr("data-id").orEmpty()
            if (animeId.isBlank()) return@runCatching null

            val json = app.get(
                "$mainUrl/ajax/episode/list/$animeId",
                referer = referer,
                headers = ajaxHeaders
            ).text
            val html = jsonResultString(json)
            val episode = Jsoup.parse(html).selectFirst("a[data-ids=\"$serverIds\"]") ?: return@runCatching null
            EpisodeMeta(
                episode.attr("data-mal"),
                episode.attr("data-slug"),
                episode.attr("data-timestamp")
            )
        }.getOrNull()
    }

    private suspend fun getMappedServerIds(meta: EpisodeMeta?): List<String> {
        val malId = meta?.malId.orEmpty()
        val slug = meta?.slug.orEmpty()
        val timestamp = meta?.timestamp.orEmpty()
        if (malId.isBlank() || slug.isBlank() || timestamp.isBlank()) return emptyList()

        return runCatching {
            val json = app.get("https://mapper.nekostream.site/api/mal/$malId/$slug/$timestamp").text
            val obj = JsonParser.parseString(json).asJsonObject
            obj.entrySet().flatMap { (_, value) ->
                if (!value.isJsonObject) return@flatMap emptyList()
                val source = value.asJsonObject
                listOfNotNull(
                    source["sub"]?.asJsonObject?.get("url")?.asString,
                    source["dub"]?.asJsonObject?.get("url")?.asString
                )
            }
        }.getOrDefault(emptyList())
    }

    private data class EpisodeMeta(
        val malId: String,
        val slug: String,
        val timestamp: String
    )

    private suspend fun loadAnikotoLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directM3u8 = getHashM3u8(url)
        if (directM3u8 != null) {
            callback.invoke(
                newExtractorLink(name, "Anikoto M3U8", directM3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = url
                }
            )
            return true
        }

        return loadExtractor(url, referer, subtitleCallback, callback)
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

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }
}
