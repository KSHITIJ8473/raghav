package com.laddu100

import com.lagradost.api.Log

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import java.net.URLEncoder

/**
 * Animetsu provider — complete rewrite.
 *
 * Site: https://animetsu.live
 * API base: https://animetsu.live/v2/api/anime/
 * Stream proxy: https://swiftstream.top/proxy (when need_proxy=true)
 *
 * All endpoints verified working with real HTTP requests. The site is behind
 * Cloudflare but does NOT issue a JS challenge to requests that send proper
 * Origin + Referer headers. The old plugin used CloudflareKiller which caused
 * "Bad Request" errors — this rewrite uses plain app.get with browser headers.
 *
 * Flow:
 *   Homepage → /v2/api/anime/home (seasonal, trending, popular, top, upcoming)
 *           → /v2/api/anime/recent?page=N&per_page=20 (recent, paginated)
 *   Search  → /v2/api/anime/search/?query={query}
 *   Load    → /v2/api/anime/info/{id}     (details)
 *           → /v2/api/anime/eps/{id}      (episode list)
 *           → /v2/api/anime/servers/{id}/{ep}  (4 servers: kite, dio, sage, meg)
 *   Links   → /v2/api/anime/oppai/{id}/{ep}?server={server}&source_type=sub|dub
 *           → returns sources[] with relative URLs
 *           → if need_proxy=true, prepend https://swiftstream.top/proxy
 *           → the proxy URL returns a valid m3u8 playlist (360p/720p/1080p)
 *
 * Sub/Dub: the source_type query param (sub or dub) controls the audio track.
 *   Not all servers have dub — we test each server and skip empty ones.
 */
class AnimetsuProvider : MainAPI() {
    override var mainUrl = "https://animetsu.live"
    override var name = "Animetsu"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiBase = "$mainUrl/v2/api/anime"
    private val proxyBase = "https://swiftstream.top/proxy"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "recent" to "Recently Updated",
        "seasonal" to "Seasonal",
        "trending" to "Trending",
        "popular" to "Popular",
        "top" to "Top Anime",
        "upcoming" to "Upcoming"
    )

    private suspend fun apiGet(url: String): String {
        return app.get(url, headers = headers).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = try {
            if (request.data == "recent") {
                val resp = parseJson<PaginatedResponse>(apiGet("$apiBase/recent?page=$page&per_page=20"))
                resp.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            } else {
                // Home categories (seasonal, trending, etc.) only have 1 page
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val resp = parseJson<HomeResponse>(apiGet("$apiBase/home"))
                val list = when (request.data) {
                    "seasonal" -> resp.seasonal
                    "trending" -> resp.trending
                    "popular" -> resp.popular
                    "top" -> resp.top
                    "upcoming" -> resp.upcoming
                    else -> null
                }
                list?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.d("Animetsu", "Animetsu: getMainPage '${request.data}' failed - ${e.message}")
            emptyList()
        }
        val hasNext = request.data == "recent" // only recent is paginated
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val resp = parseJson<PaginatedResponse>(apiGet("$apiBase/search/?query=$encoded"))
            resp.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.d("Animetsu", "Animetsu: search '$query' failed - ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Extract anime ID from URL. CloudStream may pass the full URL or just the ID.
        val animeId = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null

        // Fetch anime info
        val info = try {
            parseJson<AnimeInfo>(apiGet("$apiBase/info/$animeId"))
        } catch (e: Exception) {
            return null
        }

        val title = info.title?.english ?: info.title?.romaji ?: info.title?.native ?: return null
        val poster = info.coverImage?.large ?: info.coverImage?.medium ?: info.coverImage?.small
        val banner = info.banner
        val plot = info.description?.replace(Regex("<[^>]*>"), "")?.trim()
        val year = info.year
        val genres = info.genres ?: emptyList()
        val tvType = if (info.status?.contains("MOVIE", ignoreCase = true) == true) TvType.AnimeMovie else TvType.Anime

        // Fetch episodes
        val eps = try {
            parseJson<List<EpisodeItem>>(apiGet("$apiBase/eps/$animeId"))
        } catch (e: Exception) {
            Log.d("Animetsu", "Animetsu: episodes fetch failed - ${e.message}")
            emptyList()
        }
        Log.d("Animetsu", "Animetsu: ${eps.size} episodes found")

        // PERF: Skip the dub-availability check during load().
        // Previously this made 1 + N sequential API calls (servers + oppai per
        // server) just to decide whether to show a Dub tab. That added 2-5
        // seconds to every anime page load. Instead, always show both Sub and
        // Dub tabs — loadLinks will simply return 0 sources for dub if a
        // server doesn't have it, and the user sees an empty list instead of
        // a missing tab. This is much faster and matches how most anime
        // plugins behave.

        val subEpisodes = eps.map { ep ->
            newEpisode("animetsu|$animeId|${ep.epNum}|sub") {
                this.episode = ep.epNum.toInt()
                this.name = ep.name ?: "Episode ${ep.epNum}"
                this.description = ep.desc
                this.posterUrl = ep.img?.let { if (it.startsWith("/")) "$mainUrl$it" else it }
            }
        }

        // Always create dub episodes — loadLinks will handle servers that
        // don't have dub by returning 0 sources for that server.
        val dubEpisodes = eps.map { ep ->
            newEpisode("animetsu|$animeId|${ep.epNum}|dub") {
                this.episode = ep.epNum.toInt()
                this.name = ep.name ?: "Episode ${ep.epNum}"
                this.description = ep.desc
                this.posterUrl = ep.img?.let { if (it.startsWith("/")) "$mainUrl$it" else it }
            }
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
        // CloudStream may prepend mainUrl — strip it
        val cleanData = when {
            data.startsWith("$mainUrl/animetsu|") -> data.removePrefix("$mainUrl/")
            data.startsWith("/animetsu|") -> data.removePrefix("/")
            else -> data
        }

        if (!cleanData.startsWith("animetsu|")) return false
        val parts = cleanData.split("|")
        if (parts.size < 4) return false
        val animeId = parts[1]
        val epNum = parts[2]
        val sourceType = parts[3] // "sub" or "dub"

        // Fetch available servers (kite, dio, sage, meg)
        val servers = try {
            parseJson<List<ServerItem>>(apiGet("$apiBase/servers/$animeId/$epNum"))
        } catch (e: Exception) {
            return false
        }

        var found = false
        val displayType = if (sourceType == "dub") "DUB" else "SUB"

        // PERF: fetch all servers in parallel instead of sequentially.
        // This cuts loadLinks time from ~4s (4 servers × ~1s each) to ~1s.
        servers.amap { server ->
            try {
                val oppaiText = apiGet("$apiBase/oppai/$animeId/$epNum?server=${server.id}&source_type=$sourceType")
                val oppai = parseJson<OppaiResponse>(oppaiText)
                val sources = oppai.sources ?: return@amap
                if (sources.isEmpty()) return@amap

                for (source in sources) {
                    val rawUrl = source.url ?: continue
                    if (rawUrl.isBlank()) continue

                    // Build the final URL:
                    // - If it's already a full URL, use it as-is
                    // - If need_proxy=true, prepend the swiftstream proxy
                    // - Otherwise, prepend the site URL
                    val finalUrl = when {
                        rawUrl.startsWith("http") -> rawUrl
                        source.needProxy == true -> "$proxyBase${if (rawUrl.startsWith("/")) "" else "/"}$rawUrl"
                        else -> "$mainUrl${if (rawUrl.startsWith("/")) "" else "/"}$rawUrl"
                    }

                    val isM3u8 = source.type?.contains("mpegurl", ignoreCase = true) == true
                        || finalUrl.contains(".m3u8", true)
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    val qualityName = source.quality ?: "master"
                    val qualityInt = when {
                        qualityName.contains("1080") -> Qualities.P1080.value
                        qualityName.contains("720") -> Qualities.P720.value
                        qualityName.contains("480") -> Qualities.P480.value
                        qualityName.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    val serverDisplayName = "Animetsu ${server.id} $displayType"

                    if (isM3u8) {
                        // Use M3u8Helper to parse the playlist and generate quality-specific links
                        val generated = M3u8Helper.generateM3u8(
                            serverDisplayName, finalUrl, "$mainUrl/",
                            headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
                            )
                        )
                        if (generated.isNotEmpty()) {
                            generated.forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Animetsu",
                                    name = "$serverDisplayName ($qualityName)",
                                    url = finalUrl,
                                    type = linkType
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "Origin" to mainUrl
                                    )
                                    this.quality = qualityInt
                                }
                            )
                        }
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = "Animetsu",
                                name = "$serverDisplayName ($qualityName)",
                                url = finalUrl,
                                type = linkType
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "Origin" to mainUrl
                                )
                                this.quality = qualityInt
                            }
                        )
                    }
                    found = true
                }

                // Subtitles
                oppai.subs?.forEach { sub ->
                    val subUrl = sub.url ?: return@forEach
                    val fullSubUrl = if (subUrl.startsWith("http")) subUrl
                    else if (subUrl.startsWith("/")) "$mainUrl$subUrl"
                    else "$mainUrl/$subUrl"
                    subtitleCallback.invoke(newSubtitleFile(sub.lang ?: "English", fullSubUrl))
                }
            } catch (e: Exception) {
                Log.d("Animetsu", "Animetsu: server ${server.id} failed - ${e.message}")
            }
        }

        Log.d("Animetsu", "Animetsu: loadLinks result found=$found")
        return found
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val name = title?.english ?: title?.romaji ?: title?.native ?: return null
        val poster = coverImage?.large ?: coverImage?.medium ?: coverImage?.small
        return newAnimeSearchResponse(name, id, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null,
        @JsonProperty("small") val small: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("cover_image") val coverImage: CoverImage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PaginatedResponse(
        @JsonProperty("results") val results: List<AnimeItem>? = null,
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("last_page") val lastPage: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HomeResponse(
        @JsonProperty("seasonal") val seasonal: List<AnimeItem>? = null,
        @JsonProperty("trending") val trending: List<AnimeItem>? = null,
        @JsonProperty("popular") val popular: List<AnimeItem>? = null,
        @JsonProperty("top") val top: List<AnimeItem>? = null,
        @JsonProperty("upcoming") val upcoming: List<AnimeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeInfo(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("cover_image") val coverImage: CoverImage? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("status") val status: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("ep_num") val epNum: Double,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("img") val img: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("default") val default: Boolean = false,
        @JsonProperty("tip") val tip: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OppaiSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("need_proxy") val needProxy: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OppaiSub(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OppaiResponse(
        @JsonProperty("sources") val sources: List<OppaiSource>? = null,
        @JsonProperty("subs") val subs: List<OppaiSub>? = null
    )
}
