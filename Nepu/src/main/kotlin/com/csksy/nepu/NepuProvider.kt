package com.csksy.nepu

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

private const val TAG = "Nepu"

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SearchPayload(
    @JsonProperty("data") val data: List<SearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SearchItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SubtitlePayload(
    @JsonProperty("ok") val ok: Boolean? = null,
    @JsonProperty("results") val results: List<SubtitleItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SubtitleItem(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("language_name") val languageName: String? = null,
    @JsonProperty("release_name") val releaseName: String? = null,
    @JsonProperty("url") val url: String? = null
)

class Nepu : MainAPI() {
    override var mainUrl = "https://nepu.io"
    override var name = "Nepu"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "movies|all" to "Latest Movies",
        "shows|all" to "Latest Shows",
        "anime|all" to "Latest Anime",
        "movies|popular" to "Popular Movies",
        "shows|popular" to "Popular Shows",
        "movies|imdb" to "Top Rated Movies",
        "shows|imdb" to "Top Rated Shows",
        "movies|31" to "4K Movies",
        "movies|32" to "3D Movies",
        "shows|3" to "Cartoons",
        "anime|33" to "Subbed Anime Series",
        "anime|37" to "Dubbed Anime Series",
        "anime|35" to "Subbed Anime Movies",
        "anime|36" to "Dubbed Anime Movies"
    )

    private fun filterValue(filter: String): String = when (filter) {
        "all" -> "null"
        "popular" -> """{"sorting":"popular"}"""
        "imdb" -> """{"sorting":"imdb"}"""
        else -> """{"category":"$filter","sorting":"newest"}"""
    }

    private fun listUrl(section: String, filter: String, page: Int): String {
        val encoded = URLEncoder.encode(filterValue(filter), "UTF-8")
        return "$mainUrl/$section?filter=$encoded&page=$page"
    }

    private fun absolute(href: String): String? {
        if (href.isBlank()) return null
        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> mainUrl + href
            else -> "$mainUrl/$href"
        }
    }

    private fun cardType(href: String, request: MainPageRequest): TvType {
        val isMovie = href.contains("/movie/")
        return when (request.data.substringBefore("|")) {
            "anime" -> if (isMovie) TvType.AnimeMovie else TvType.Anime
            "shows" -> if (request.data.endsWith("|3")) TvType.Cartoon else TvType.TvSeries
            else -> if (isMovie) TvType.Movie else TvType.TvSeries
        }
    }

    private fun parseCards(doc: Document, request: MainPageRequest): List<SearchResponse> {
        return doc.select("div.list-movie").mapNotNull { card ->
            val href = absolute(card.selectFirst("a[href]")?.attr("href") ?: "") ?: return@mapNotNull null
            if (!href.contains("/movie/") && !href.contains("/show/")) return@mapNotNull null
            val title = card.selectFirst(".list-caption a")?.text()?.trim() ?: return@mapNotNull null
            val poster = card.selectFirst(".media-cover")?.attr("data-src")?.ifBlank { null }
            val rating = card.selectFirst(".list-media-attr .imdb span")?.text()
                ?.trim()?.replace(",", ".")?.toDoubleOrNull()
            newMovieSearchResponse(title, href, cardType(href, request)) {
                this.posterUrl = poster
                this.score = rating?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val (section, filter) = request.data.split("|", limit = 2)
        val response = try {
            nepuGet(listUrl(section, filter, page))
        } catch (e: Exception) {
            Log.d(TAG, "main page $section: ${e.message}")
            throw ErrorLoadingException("Could not reach Nepu. Open provider settings and verify Cloudflare access.")
        }
        if (response.code != 200) {
            throw ErrorLoadingException("Nepu returned ${response.code}")
        }
        val doc = Jsoup.parse(response.text, mainUrl)
        val items = parseCards(doc, request)
        val hasNext = doc.select("ul.pagination li a").any { link ->
            link.text().trim().equals("Next", ignoreCase = true) &&
                link.parent()?.hasClass("disabled") != true
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(Regex("\\s+"), "-")
        if (q.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val response = try {
            nepuGet(
                "$mainUrl/ajax/posts?q=$encoded",
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "search: ${e.message}")
            return emptyList()
        }
        if (response.code != 200) return emptyList()
        val payload = try {
            parseJson<SearchPayload>(response.text)
        } catch (e: Exception) {
            Log.d(TAG, "search parse: ${e.message}")
            return emptyList()
        }
        return payload.data.orEmpty().mapNotNull { item ->
            val href = absolute(item.url ?: "") ?: return@mapNotNull null
            if (!href.contains("/movie/") && !href.contains("/show/")) return@mapNotNull null
            val rawName = item.name?.trim() ?: return@mapNotNull null
            val isAnime = rawName.startsWith("[SUB]") || rawName.startsWith("[DUB]")
            val type = when {
                isAnime && href.contains("/movie/") -> TvType.AnimeMovie
                isAnime -> TvType.Anime
                href.contains("/movie/") -> TvType.Movie
                else -> TvType.TvSeries
            }
            newMovieSearchResponse(rawName, href, type) {
                this.posterUrl = item.image?.ifBlank { null }
            }
        }
    }

    private fun attrText(doc: Document, label: String): String? {
        return doc.select(".detail-text .video-attr").firstOrNull { block ->
            block.selectFirst(".attr")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst(".text")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = try {
            nepuGet(url)
        } catch (e: Exception) {
            Log.d(TAG, "load: ${e.message}")
            throw ErrorLoadingException("Could not reach Nepu. Open provider settings and verify Cloudflare access.")
        }
        if (response.code != 200) {
            throw ErrorLoadingException("Nepu returned ${response.code} for this title")
        }
        val doc = Jsoup.parse(response.text, url)

        val title = doc.selectFirst(".detail-text h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst(".detail-content .cover .media-cover")?.attr("data-src")?.ifBlank { null }
            ?: doc.selectFirst(".detail-content .cover .media-cover")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
        val year = attrText(doc, "Release Date")?.take(4)?.toIntOrNull()
        val rating = attrText(doc, "IMDB")?.replace(",", ".")?.toDoubleOrNull()
        val plot = attrText(doc, "Overview")
        val genres = doc.select(".detail-text .video-attr a[href*='/category/']").eachText()

        val episodes = doc.select(".episodes > div.tab-pane").flatMap { pane ->
            val season = pane.id().filter { it.isDigit() }.toIntOrNull() ?: 1
            pane.select("a[href]").mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { absolute(link.attr("href")) ?: "" }
                if (href.isBlank()) return@mapNotNull null
                val number = link.selectFirst(".episode")?.text()
                    ?.trim()?.substringAfterLast(' ')?.toIntOrNull()
                val name = link.selectFirst(".name")?.text()?.trim()
                newEpisode(href) {
                    this.season = season
                    this.episode = number
                    this.name = name
                    this.posterUrl = poster
                }
            }
        }

        val isAnimeTitle = title.startsWith("[SUB]") || title.startsWith("[DUB]")
        return if (episodes.isNotEmpty()) {
            val type = when {
                isAnimeTitle -> TvType.Anime
                url.contains("/show/") && genres.any { it.equals("Animation", true) } -> TvType.Cartoon
                else -> TvType.TvSeries
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, if (isAnimeTitle) TvType.AnimeMovie else TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.tags = genres
            }
        }
    }

    private fun qualityFromLabel(label: String): Int = when {
        label.contains("4K", true) || label.contains("Ultra HD", true) -> Qualities.P2160.value
        label.contains("Full HD", true) -> Qualities.P1080.value
        label.contains("HD", true) -> Qualities.P720.value
        label.contains("SD", true) -> Qualities.P480.value
        label.contains("CAM", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = try {
            nepuGet(data)
        } catch (e: Exception) {
            Log.d(TAG, "loadLinks page: ${e.message}")
            return false
        }
        if (pageResponse.code != 200) return false
        val doc = Jsoup.parse(pageResponse.text, data)

        val sources = doc.select("a[data-embed]").mapNotNull { el ->
            val id = el.attr("data-embed").trim()
            if (id.isEmpty()) return@mapNotNull null
            val label = el.selectFirst(".name")?.text()?.trim()
                ?: el.selectFirst(".source-selected")?.text()?.trim()
                ?: "Source"
            id to label
        }.distinctBy { it.first }

        var found = false
        for ((id, label) in sources) {
            try {
                val embedResponse = nepuPostForm(
                    "$mainUrl/ajax/embed",
                    mapOf("id" to id),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    )
                )
                if (embedResponse.code != 200) continue
                val relay = Regex("relayManifestUrl\\s*=\\s*\"([^\"]+)\"")
                    .find(embedResponse.text)?.groupValues?.get(1) ?: continue
                val manifestUrl = absolute(relay.replace("\\u0026", "&")) ?: continue

                val playbackHeaders = buildMap {
                    put("Origin", mainUrl)
                    put("Referer", "$mainUrl/")
                    put("User-Agent", NepuSession.userAgent())
                    NepuSession.cookies()?.let { put("Cookie", it) }
                }
                callback.invoke(
                    newExtractorLink(source = name, name = label, url = manifestUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                        this.quality = qualityFromLabel(label)
                    }
                )
                found = true
            } catch (e: Exception) {
                Log.d(TAG, "source $label: ${e.message}")
            }
        }

        loadCachedSubtitles(doc, sources.firstOrNull()?.first, subtitleCallback)
        return found
    }

    private suspend fun loadCachedSubtitles(
        doc: Document,
        videoId: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val panel = doc.selectFirst("#nepuSubtitlePanel") ?: return
        val contentId = panel.attr("data-content-id").trim().toIntOrNull() ?: return
        val episodeId = panel.attr("data-episode-id").trim().toIntOrNull() ?: 0
        try {
            val response = nepuPostForm(
                "$mainUrl/ajax/subtitlecached",
                mapOf(
                    "content_id" to contentId.toString(),
                    "episode_id" to episodeId.toString(),
                    "video_id" to (videoId ?: "0")
                ),
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                )
            )
            if (response.code != 200) return
            val payload = parseJson<SubtitlePayload>(response.text)
            if (payload.ok != true) return
            payload.results.orEmpty().forEach { item ->
                val path = item.url?.takeIf { it.startsWith("/") } ?: return@forEach
                val language = item.language?.trim().orEmpty().ifBlank { "en" }
                val label = listOfNotNull(
                    item.languageName?.trim()?.takeIf { it.isNotBlank() },
                    item.releaseName?.trim()?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { "Subtitle" }
                subtitleCallback.invoke(
                    newSubtitleFile(label, mainUrl + path) {
                        this.lang = language
                        this.headers = buildMap {
                            put("Referer", "$mainUrl/")
                            put("User-Agent", NepuSession.userAgent())
                            NepuSession.cookies()?.let { put("Cookie", it) }
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "subtitles: ${e.message}")
        }
    }
}
