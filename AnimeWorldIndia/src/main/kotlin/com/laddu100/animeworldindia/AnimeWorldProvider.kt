package com.laddu100.animeworldindia

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.top"
    override var name = "Anime World India"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    private val TAG = "AnimeWorld"

    @Volatile
    private var isUrlLoaded = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseConfig(
        @JsonProperty("animeworldindia_url") val animeworldindia_url: String? = null,
        @JsonProperty("animeworld_url") val animeworld_url: String? = null,
        @JsonProperty("awi_url") val awi_url: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 10_000L).text
            val config = parseJson<FirebaseConfig>(response)
            val url = config.animeworldindia_url ?: config.animeworld_url ?: config.awi_url
            if (!url.isNullOrBlank()) mainUrl = url.removeSuffix("/")
            isUrlLoaded = true
        } catch (e: Exception) {
            isUrlLoaded = true
        }
    }

    override val mainPage = mainPageOf(
        "home" to "Latest Series",
        "franchise" to "Franchises",
        "trending" to "Trending Anime",
        "popular" to "Popular Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(mainUrl)
            val doc = Jsoup.parse(response.text)

            val allItems = doc.select("li.post, article.post").mapNotNull { el ->
                val link = el.selectFirst("a[href*=/series/]")?.attr("href") ?: return@mapNotNull null
                if (link.endsWith("/series/")) return@mapNotNull null
                val title = el.selectFirst("h2, h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.startsWith("//")) "https:$src" else src
                }
                newAnimeSearchResponse(title, link, TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            when (request.data) {
                "home" -> newHomePageResponse("Latest Series", allItems.take(20), hasNext = false)
                "franchise" -> {
                    val items = doc.select("a[href*=/category/franchise/]").mapNotNull { el ->
                        val href = el.attr("href")
                        val title = el.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val poster = el.selectFirst("img")?.let { img ->
                            val src = img.attr("data-src").ifBlank { img.attr("src") }
                            if (src.startsWith("//")) "https:$src" else src
                        }
                        newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
                    }.distinctBy { it.url }
                    newHomePageResponse("Franchises", items, hasNext = false)
                }
                "trending" -> newHomePageResponse("Trending Anime", allItems.shuffled().take(20), hasNext = false)
                "popular" -> newHomePageResponse("Popular Series", allItems.reversed().take(20), hasNext = false)
                else -> newHomePageResponse(request.name, emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        loadFirebaseUrl()
        val results = mutableListOf<SearchResponse>()
        return try {
            for (pageNum in 1..3) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val response = animeWorldGet("$mainUrl/page/$pageNum/?s=$encoded")
                val doc = Jsoup.parse(response.text)
                val items = doc.select("li.post, article.post").mapNotNull { el ->
                    val seriesLink = el.selectFirst("a[href*=/series/]")?.attr("href")
                    val movieLink = el.selectFirst("a[href*=/movies/]")?.attr("href")
                    val link = seriesLink ?: movieLink ?: return@mapNotNull null
                    if (link.endsWith("/series/") || link.endsWith("/movies/")) return@mapNotNull null
                    val title = el.selectFirst("h2, h3")?.text()?.trim() ?: return@mapNotNull null
                    val poster = el.selectFirst("img")?.let { img ->
                        val src = img.attr("data-src").ifBlank { img.attr("src") }
                        if (src.startsWith("//")) "https:$src" else src
                    }
                    val tvType = if (link.contains("/movies/")) TvType.AnimeMovie else TvType.Anime
                    newAnimeSearchResponse(title, link, tvType) { this.posterUrl = poster }
                }.distinctBy { it.url }
                if (items.isEmpty()) break
                results.addAll(items)
            }
            results.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(url)
            val doc = Jsoup.parse(response.text)

            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst(".poster img, .post-thumbnail img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            }
            val backdrop = doc.selectFirst(".backdrop img, .bg img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            }
            val plot = doc.selectFirst(".description, .synopsis, .plot, p")?.text()?.trim()
            val genres = doc.select("a[href*=genre]").map { it.text().trim() }.filter { it.isNotBlank() }

            val isMovie = url.contains("/movies/")

            if (isMovie) {
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop ?: poster
                    this.plot = plot
                    this.tags = genres
                }
            }

            val postId = doc.select("[data-post]").firstOrNull()?.attr("data-post") ?: ""

            val seasonTabs = doc.select("[data-season]").mapNotNull { el ->
                val seasonNum = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                val seasonName = el.text()?.trim()?.ifBlank { "Season $seasonNum" } ?: "Season $seasonNum"
                Pair(seasonNum, seasonName)
            }.distinctBy { it.first }.sortedBy { it.first }

            val initialEpLinks = doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
            val initialSeasonNum = initialEpLinks.firstOrNull()?.let {
                Regex("""(\d+)x(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: (seasonTabs.firstOrNull()?.first ?: 1)

            val episodes = mutableListOf<Episode>()

            val allSeasons = seasonTabs.map { it.first }.ifEmpty { listOf(initialSeasonNum) }
            val fullSeasons = (1..(allSeasons.maxOrNull() ?: initialSeasonNum)).toList()

            for (season in fullSeasons) {
                val epLinks = if (season == initialSeasonNum && initialEpLinks.isNotEmpty()) {
                    initialEpLinks
                } else {
                    fetchSeasonEpisodes(postId, season)
                }
                for (epUrl in epLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.count { it.season == season } + 1)
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: season
                    val cleanName = cleanEpisodeName(epUrl)
                    episodes.add(newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = cleanName
                    })
                }
            }

            if (episodes.isEmpty() && initialEpLinks.isNotEmpty()) {
                for (epUrl in initialEpLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.size + 1)
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    episodes.add(newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = cleanEpisodeName(epUrl)
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = plot
                this.tags = genres
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    private fun cleanEpisodeName(epUrl: String): String {
        val raw = epUrl.substringAfter("/episode/").trimEnd('/')
        val match = Regex("""(\d+)x(\d+)""").find(raw)
        val epNum = match?.groupValues?.get(2)?.toIntOrNull()
        val withoutNum = raw.replace(Regex("""\d+x\d+"""), "").trim('-')
        val parts = withoutNum.split("-").filter { it.isNotBlank() && it != "ep" && it != "episode" }
        val name = parts.joinToString(" ")
            .replace(Regex("\\s+/\\s*"), " ")
            .replace("/", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (name.isNotBlank()) name else "Episode ${epNum ?: ""}".trim()
    }

    private suspend fun fetchSeasonEpisodes(postId: String, season: Int): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId"
            val response = animeWorldGet(ajaxUrl, headers = mapOf(
                "Referer" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val doc = Jsoup.parse(response.text)
            doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
        } catch (e: Exception) {
            Log.e(TAG, "fetchSeasonEpisodes: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(data)
            val doc = Jsoup.parse(response.text)

            val iframes = doc.select("iframe").mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }
                if (src.isNotBlank()) src else null
            }.distinct()

            var found = false
            for (iframe in iframes) {
                if (iframe.contains("zephyrflick")) {
                    val videoId = Regex("/video/([a-f0-9]+)").find(iframe)?.groupValues?.get(1) ?: continue
                    val resolved = resolveZephyrFlick(videoId, subtitleCallback, callback)
                    if (resolved) found = true
                }
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            false
        }
    }

    private suspend fun resolveZephyrFlick(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = animeWorldGet("https://play.zephyrflick.top/video/$videoId", headers = mapOf(
                "Referer" to mainUrl
            ))
            val html = response.text
            val allHashes = Regex("[a-f0-9]{32}").findAll(html).map { it.value }.distinct().toList()
            val cdnHash = allHashes.firstOrNull { it != videoId } ?: return false

            val m3u8Url = "https://s7.as-cdn13.top/cdn/down/$cdnHash/master.m3u8"

            val subtitleUrl = Regex("https?://[^\"'\\s]*Subtitle/[^\"'\\s]+\\.srt").find(html)?.value
            if (subtitleUrl != null) {
                subtitleCallback.invoke(SubtitleFile("en", subtitleUrl))
            }

            val link = newExtractorLink(
                "Anime World India",
                "Anime World India - ZephyrFlick",
                m3u8Url,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "https://play.zephyrflick.top/"
                this.headers = mapOf("Referer" to "https://play.zephyrflick.top/")
            }
            callback.invoke(link)
            true
        } catch (e: Exception) {
            Log.e(TAG, "resolveZephyrFlick: ${e.message}")
            false
        }
    }
}
