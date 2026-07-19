package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AnimoTvSlashProvider : MainAPI() {
    override var mainUrl = "https://animotvslash.org"
    override var name = "AnimoTvSlash"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val apiBase = "https://animotvslash.org/wp-json"
    private val TAG = "AnimoTvSlash"

    // Static mainPage — ALL rows use wp/v2/anime with _embed=true (posters work)
    override val mainPage = mainPageOf(
        "$apiBase/wp/v2/anime?per_page=20&page=1&orderby=date&order=desc&_embed=true" to "Recently Added",
        "$apiBase/wp/v2/anime?per_page=20&page=2&orderby=date&order=desc&_embed=true" to "Latest Anime",
        "$apiBase/wp/v2/anime?per_page=20&page=3&orderby=date&order=desc&_embed=true" to "Latest (Page 3)",
        "$apiBase/wp/v2/anime?per_page=20&page=1&orderby=title&order=asc&_embed=true" to "A-Z",
        "$apiBase/wp/v2/anime?per_page=20&page=4&orderby=date&order=desc&_embed=true" to "Browse"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchAnimeResponse(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("slug") val slug: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpAnimeList(
        @JsonProperty("id") val id: Int,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: WpTitle? = null,
        @JsonProperty("_embedded") val embedded: WpEmbedded? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpTitle(
        @JsonProperty("rendered") val rendered: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpEmbedded(
        @JsonProperty("wp:featuredmedia") val featuredMedia: List<WpMedia>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpMedia(
        @JsonProperty("source_url") val sourceUrl: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpAnimeDetail(
        @JsonProperty("id") val id: Int,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: WpTitle? = null,
        @JsonProperty("content") val content: WpContent? = null,
        @JsonProperty("class_list") val classList: List<String>? = null,
        @JsonProperty("yoast_head_json") val yoast: YoastHead? = null,
        @JsonProperty("_embedded") val embedded: WpEmbedded? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpContent(
        @JsonProperty("rendered") val rendered: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YoastHead(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FindEpisodeResponse(
        @JsonProperty("id") val id: Int? = null
    )

    /**
     * Extract animeId and slug from the URL passed by CloudStream.
     * CloudStream prepends mainUrl to our search data, so:
     *   "https://animotvslash.org|12345|my-slug" → (12345, "my-slug")
     *   "12345|my-slug" → (12345, "my-slug")
     */
    private fun parseLoadUrl(url: String): Pair<Int, String>? {
        Log.d(TAG, "parseLoadUrl START: url='$url'")
        // Strip mainUrl prefix if present (CloudStream may prepend https://animotvslash.org/)
        val cleanUrl = url
            .removePrefix("$mainUrl/")
            .removePrefix("$mainUrl|")
            .removePrefix(mainUrl)
        val parts = cleanUrl.split("|")
        if (parts.size < 2) {
            Log.e(TAG, "parseLoadUrl: invalid format (got ${parts.size} parts from '$cleanUrl')")
            return null
        }
        val animeId = parts[0].trim().toIntOrNull()
        val slug = parts[1].trim()
        if (animeId == null || slug.isBlank()) {
            Log.e(TAG, "parseLoadUrl: animeId parse failed or slug empty (id='${parts[0]}', slug='$slug')")
            return null
        }
        Log.d(TAG, "parseLoadUrl: OK animeId=$animeId slug='$slug'")
        return Pair(animeId, slug)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val startTime = System.currentTimeMillis()
            val res = app.get(request.data, timeout = 30_000L)
            val elapsed = System.currentTimeMillis() - startTime

            val home: List<SearchResponse> = if (request.data.contains("/animo/v1/search-anime")) {
                // search-anime returns [{id, title, slug}] — NO poster data
                val items = parseJson<List<SearchAnimeResponse>>(res.text)
                // For search-anime, we don't have posters. Return without posters.
                items.mapNotNull { it.toSearchResult() }
            } else {
                // wp/v2/anime with _embed returns [{id, slug, title, _embedded.wp:featuredmedia[0].source_url}]
                val items = parseJson<List<WpAnimeList>>(res.text)
                Log.d(TAG, "getMainPage: parsed ${items.size} items from wp/v2/anime")
                items.mapNotNull { it.toSearchResult() }
            }

            Log.d(TAG, "getMainPage: '${request.name}' -> ${home.size} results (with posters: ${home.count { it.posterUrl != null }})")
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED for '${request.name}': ${e.message}")
            Log.e(TAG, "getMainPage error: ${e.stackTraceToString().take(400)}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun SearchAnimeResponse.toSearchResult(): SearchResponse? {
        if (title.isBlank()) return null
        val isDub = slug.endsWith("-dub") || title.contains("(Dub)", ignoreCase = true)
        Log.d(TAG, "toSearchResult: id=$id slug=$slug title='$title' isDub=$isDub")
        // Data format: "$mainUrl|$id|$slug" — prefix with mainUrl so CloudStream doesn't prepend it
        return newAnimeSearchResponse(title, "$mainUrl|$id|$slug", TvType.Anime) {
            addDubStatus(dubExist = isDub, subExist = !isDub)
        }
    }

    private fun WpAnimeList.toSearchResult(): SearchResponse? {
        val title = title?.rendered ?: return null
        if (title.isBlank()) return null
        val isDub = slug.endsWith("-dub") || title.contains("(Dub)", ignoreCase = true)
        val poster = embedded?.featuredMedia?.firstOrNull()?.sourceUrl
        // Data format: "$mainUrl|$id|$slug"
        return newAnimeSearchResponse(title, "$mainUrl|$id|$slug", TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = isDub, subExist = !isDub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$apiBase/animo/v1/search-anime?q=$encoded"
            val res = app.get(url, timeout = 30_000L)

            val items = parseJson<List<SearchAnimeResponse>>(res.text)
            Log.d(TAG, "search: parsed ${items.size} items")
            val results = items.mapNotNull { it.toSearchResult() }
            Log.d(TAG, "search END: '$query' -> ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED for '$query': ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val parsed = parseLoadUrl(url) ?: return null
        val animeId = parsed.first
        val slug = parsed.second

        return try {
            // 1. Fetch series detail (CF-free, with _embed for poster — NO _fields so _embedded works)
            val detailUrl = "$apiBase/wp/v2/anime?slug=$slug&_embed=true"
            val detailStart = System.currentTimeMillis()
            val detailRes = app.get(detailUrl, timeout = 30_000L)
            val detailElapsed = System.currentTimeMillis() - detailStart

            val detailList = parseJson<List<WpAnimeDetail>>(detailRes.text)
            val detail = detailList.firstOrNull() ?: run {
                Log.e(TAG, "load: [1/3] no anime found for slug=$slug. Response: ${detailRes.text.take(200)}")
                return null
            }

            val title = detail.title?.rendered ?: slug
            val plot = detail.content?.rendered?.let { Jsoup.parse(it).text() }
            val poster = detail.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            val genres = detail.classList
                ?.filter { it.startsWith("genres-") }
                ?.map { it.removePrefix("genres-").replace("-", " ").replaceFirstChar { it.uppercase() } }

            // 2. Find episodes — FAST: binary search for last ep, then fetch all in parallel
            val episodePostIds = mutableMapOf<Int, Int>() // epNum -> postId

            val ep1Url = "$apiBase/animo/v1/find-episode?seri=$animeId&ep=1"
            val ep1Res = app.get(ep1Url, timeout = 15_000L)
            val ep1Parsed = parseJson<FindEpisodeResponse>(ep1Res.text)
            if (ep1Parsed.id == null) {
            } else {
                episodePostIds[1] = ep1Parsed.id
                var lastFound = 1
                var probe = 2
                while (probe <= 2048) {
                    val probeUrl = "$apiBase/animo/v1/find-episode?seri=$animeId&ep=$probe"
                    try {
                        val probeRes = app.get(probeUrl, timeout = 15_000L)
                        val probeParsed = parseJson<FindEpisodeResponse>(probeRes.text)
                        if (probeParsed.id != null) {
                            lastFound = probe
                            probe *= 2
                        } else {
                            break
                        }
                    } catch (e: Exception) { break }
                }
                // Binary search between lastFound and probe to find exact last ep
                var lo = lastFound + 1
                var hi = probe - 1
                var lastEp = lastFound
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val midUrl = "$apiBase/animo/v1/find-episode?seri=$animeId&ep=$mid"
                    try {
                        val midRes = app.get(midUrl, timeout = 15_000L)
                        val midParsed = parseJson<FindEpisodeResponse>(midRes.text)
                        if (midParsed.id != null) {
                            lastEp = mid
                            lo = mid + 1
                        } else {
                            hi = mid - 1
                        }
                    } catch (e: Exception) { hi = mid - 1 }
                }

                if (lastEp > 1) {
                    coroutineScope {
                        for (batchStart in 2..lastEp step 20) {
                            val batchEnd = minOf(batchStart + 19, lastEp)
                            val deferreds = (batchStart..batchEnd).map { ep ->
                                async {
                                    val epUrl = "$apiBase/animo/v1/find-episode?seri=$animeId&ep=$ep"
                                    try {
                                        val epRes = app.get(epUrl, timeout = 15_000L)
                                        val parsed = parseJson<FindEpisodeResponse>(epRes.text)
                                        ep to parsed.id
                                    } catch (e: Exception) { ep to null }
                                }
                            }
                            deferreds.forEach { d ->
                                val (ep, postId) = d.await()
                                if (postId != null) episodePostIds[ep] = postId
                            }
                        }
                    }
                }
            }

            if (episodePostIds.isEmpty()) {
                Log.e(TAG, "load: [2/3] NO episodes found for animeId=$animeId")
                return null
            }

            // 3. Build episode list
            val isDub = slug.endsWith("-dub") || title.contains("(Dub)", ignoreCase = true)
            val tvType = TvType.Anime
            Log.d(TAG, "load: [3/3] isDub=$isDub tvType=$tvType")

            val episodes = episodePostIds.toList().sortedBy { it.first }.map { (epNum, postId) ->
                // Data format: "$mainUrl|$postId|$epNum|$isDub"
                newEpisode("$mainUrl|$postId|$epNum|$isDub") {
                    this.episode = epNum
                    this.name = "$title Episode $epNum"
                }
            }

            val dubStatus = if (isDub) DubStatus.Dubbed else DubStatus.Subbed
            Log.d(TAG, "load END: success animeId=$animeId title='$title' episodes=${episodes.size} dubStatus=$dubStatus")
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                addEpisodes(dubStatus, episodes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED for animeId=$animeId slug=$slug: ${e.message}")
            Log.e(TAG, "load error: ${e.stackTraceToString().take(500)}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Format: $mainUrl|$postId|$epNum|$isDub
        val parts = data.split("|")
        if (parts.size < 4) {
            Log.e(TAG, "loadLinks: invalid data format (got ${parts.size} parts): '$data'")
            return false
        }
        val postId = parts[1]
        val epNum = parts[2]
        val isDub = parts[3] == "true"

        return try {
            // The player-embed page uses video.js which injects <source> tags via JavaScript.
            // Static HTML parsing won't find them. We use THREE approaches in order:
            // 1. cfAppGet (CF bypass) + parse static HTML for <source> tags (older episodes)
            // 2. Check legacy HLS iframe (?hls=<base64>)
            // 3. WebViewResolver to render the page + intercept .mp4/.m3u8 URLs (video.js JS injection)
            val embedUrl = "$mainUrl/player-embed/id/$postId/"
            val embedStart = System.currentTimeMillis()
            val res = cfAppGet(embedUrl)
            val embedElapsed = System.currentTimeMillis() - embedStart

            var found = false

            if (!isAnimoTvSlashCloudflareBlocked(res) && res.text.isNotBlank()) {
                val html = res.text

                val mp4Urls = mutableListOf<String>()
                val m3u8Urls = mutableListOf<String>()
                Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(html).forEach {
                    mp4Urls.add(it.value.replace("\\/", "/"))
                }
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html).forEach {
                    m3u8Urls.add(it.value.replace("\\/", "/"))
                }

                val doc = Jsoup.parse(html)
                val sourceTags = doc.select("source")

                val dataSetup = doc.selectFirst("video-js[data-setup]")?.attr("data-setup")
                if (dataSetup != null) {
                    // Extract URLs from data-setup JSON
                    val dataSetupUrls = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""").findAll(dataSetup)
                        .map { it.value.replace("\\/", "/") }
                        .distinct()
                        .toList()
                    mp4Urls.addAll(dataSetupUrls)
                }

                // Process <source> tags
                if (sourceTags.isNotEmpty()) {
                    for (source in sourceTags) {
                        val src = source.attr("src")
                        val label = source.attr("label")
                        if (src.isBlank()) continue
                        val quality = when {
                            label.contains("1080") -> Qualities.P1080.value
                            label.contains("720") -> Qualities.P720.value
                            label.contains("480") -> Qualities.P480.value
                            label.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        val sourceName = "$name - ${if (isDub) "Dub" else "Sub"}" + (if (label.isNotBlank()) " ($label)" else "")
                        callback.invoke(
                            newExtractorLink(source = sourceName, name = sourceName, url = src, type = ExtractorLinkType.VIDEO) {
                                this.quality = quality
                            }
                        )
                        found = true
                    }
                }

                // Process regex-found .mp4 URLs (if no <source> tags found)
                if (!found && mp4Urls.isNotEmpty()) {
                    for (url in mp4Urls) {
                        val quality = when {
                            url.contains("1080") -> Qualities.P1080.value
                            url.contains("720") -> Qualities.P720.value
                            url.contains("480") -> Qualities.P480.value
                            url.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        val sourceName = "$name - ${if (isDub) "Dub" else "Sub"}"
                        callback.invoke(
                            newExtractorLink(source = sourceName, name = sourceName, url = url, type = ExtractorLinkType.VIDEO) {
                                this.quality = quality
                            }
                        )
                        found = true
                    }
                }

                // Process regex-found .m3u8 URLs
                if (!found && m3u8Urls.isNotEmpty()) {
                    for (url in m3u8Urls) {
                        val sourceName = "$name - ${if (isDub) "Dub" else "Sub"}"
                        callback.invoke(
                            newExtractorLink(source = sourceName, name = sourceName, url = url, type = ExtractorLinkType.M3U8) {
                                this.referer = mainUrl
                            }
                        )
                        found = true
                    }
                }

                // Check for legacy HLS iframe (?hls=<base64>)
                if (!found) {
                    val iframe = doc.selectFirst("iframe[src*=?hls=]")
                    if (iframe != null) {
                        val src = iframe.attr("src")
                        val hlsParam = src.substringAfter("?hls=").substringBefore("&")
                        val m3u8Url = try {
                            String(java.util.Base64.getDecoder().decode(hlsParam), Charsets.UTF_8)
                        } catch (e: Exception) { null }
                        if (m3u8Url != null && m3u8Url.contains(".m3u8")) {
                            val sourceName = "$name - ${if (isDub) "Dub" else "Sub"}"
                            callback.invoke(
                                newExtractorLink(source = sourceName, name = sourceName, url = m3u8Url, type = ExtractorLinkType.M3U8) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                    }
                }

                if (!found) {
                    Log.e(TAG, "loadLinks: [2/3] NO stream URLs found in HTML. Searching for clues...")
                    // Log any videas.fr URLs
                    val videasUrls = Regex("""https?://[^\s"'<>]*videas\.fr[^\s"'<>]*""").findAll(html)
                        .map { it.value }.distinct().toList()
                    Log.d(TAG, "loadLinks: [2/3] videas.fr URLs: ${videasUrls.size}")
                    videasUrls.take(3).forEach { Log.d(TAG, "  videas: $it") }
                    // Log any video-related tags
                    val videoTags = doc.select("video, video-js, iframe")
                    Log.d(TAG, "loadLinks: [2/3] video/iframe tags: ${videoTags.size}")
                    videoTags.take(3).forEach { }
                }
            } else {
                Log.e(TAG, "loadLinks: [1/3] CF-blocked or empty response — will try WebViewResolver directly")
            }

            // 3. If static HTML parsing found nothing, use WebViewResolver to render the page
            //    and intercept .mp4/.m3u8 URLs (video.js injects them via JavaScript)
            if (!found) {
                Log.d(TAG, "loadLinks: [3/3] static parsing found nothing — using WebViewResolver to intercept stream")
                try {
                    val resolver = com.lagradost.cloudstream3.network.WebViewResolver(
                        interceptUrl = Regex("""(?i)\.(mp4|m3u8)(?:\?|$)"""),
                        additionalUrls = listOf(Regex("""(?i)\.(mp4|m3u8)(?:\?|$)""")),
                        script = """document.querySelector('video,[role="button"],.vjs-big-play-button')?.click();""",
                        useOkhttp = false,
                        timeout = 30_000L
                    )
                    val resolvedUrl = app.get(embedUrl, referer = "$mainUrl/", interceptor = resolver).url
                    Log.d(TAG, "loadLinks: [3/3] WebViewResolver resolved URL: $resolvedUrl")

                    if (resolvedUrl.contains(".mp4", ignoreCase = true) || resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                        val sourceName = "$name - ${if (isDub) "Dub" else "Sub"}"
                        callback.invoke(
                            newExtractorLink(
                                source = sourceName,
                                name = sourceName,
                                url = resolvedUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                            }
                        )
                        found = true
                        Log.d(TAG, "loadLinks: [3/3] WebViewResolver found stream URL")
                    } else {
                        Log.e(TAG, "loadLinks: [3/3] WebViewResolver did not intercept .mp4/.m3u8 URL (got: $resolvedUrl)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks: [3/3] WebViewResolver failed: ${e.message}")
                }
            }

            if (!found) {
                Log.e(TAG, "loadLinks: NO links extracted (tried static HTML + legacy HLS + WebViewResolver)")
            } else {
                Log.d(TAG, "loadLinks END: success for postId=$postId epNum=$epNum")
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FAILED: ${e.message}")
            Log.e(TAG, "loadLinks error: ${e.stackTraceToString().take(500)}")
            false
        }
    }
}
