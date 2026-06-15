package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder

class AnimetsuProvider : MainAPI() {
    override var mainUrl = "https://animetsu.live"
    override var name = "Animetsu (disabled)"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "recent" to "Recently Updated",
        "seasonal" to "Seasonal",
        "trending" to "Trending",
        "popular" to "Popular",
        "top" to "Top Anime",
        "upcoming" to "Upcoming"
    )

    private val apiBase = "https://animetsu.live/v2/api"
    private val proxyBase = "https://swiftstream.top/proxy"
    private val cfKiller = CloudflareKiller()

    private suspend fun apiGet(url: String): String {
        return app.get(
            url = url,
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            ),
            interceptor = cfKiller
        ).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (request.data == "recent") {
            val responseText = apiGet("$apiBase/anime/recent?page=$page&per_page=20")
            val response = parseJson<PaginatedResponse>(responseText)
            response.results?.map { it.toSearchResponse() } ?: emptyList()
        } else {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val responseText = apiGet("$apiBase/anime/home")
            val response = parseJson<HomeResponse>(responseText)
            val list = when (request.data) {
                "seasonal" -> response.seasonal
                "trending" -> response.trending
                "popular" -> response.popular
                "top" -> response.top
                "upcoming" -> response.upcoming
                else -> emptyList()
            }
            list?.map { it.toSearchResponse() } ?: emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val responseText = apiGet("$apiBase/anime/search/?query=$encodedQuery")
        val response = parseJson<PaginatedResponse>(responseText)
        return response.results?.map { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = if (url.startsWith("http")) {
            url.substringAfter("/info/").substringBefore("/")
        } else {
            url
        }

        val infoText = apiGet("$apiBase/anime/info/$animeId")
        val info = parseJson<AnimeInfo>(infoText)

        val epsText = apiGet("$apiBase/anime/eps/$animeId")
        val eps = parseJson<List<EpisodeItem>>(epsText)

        val title = info.title?.english ?: info.title?.romaji ?: info.title?.native ?: "Unknown"
        val poster = info.coverImage?.large ?: info.coverImage?.medium ?: info.coverImage?.small
        val banner = info.banner
        val plot = info.description?.replace(Regex("<[^>]*>"), "")
        val year = info.year
        val genres = info.genres ?: emptyList()

        val tvType = if (info.status?.contains("MOVIE", ignoreCase = true) == true) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        // Check if a dubbed version exists by checking the first episode
        var hasDub = false
        if (eps.isNotEmpty()) {
            val firstEpNum = eps[0].epNum
            val serversText = apiGet("$apiBase/anime/servers/$animeId/$firstEpNum")
            val servers = parseJson<List<ServerItem>>(serversText)
            if (servers.isNotEmpty()) {
                val defaultServer = servers.firstOrNull { it.default } ?: servers[0]
                val oppaiText = apiGet("$apiBase/anime/oppai/$animeId/$firstEpNum?server=${defaultServer.id}&source_type=dub")
                val oppaiRes = parseJson<OppaiResponse>(oppaiText)
                if (oppaiRes.sources != null && oppaiRes.sources.isNotEmpty()) {
                    hasDub = true
                }
            }
        }

        val subEpisodes = eps.map { ep ->
            newEpisode("animetsu|$animeId|${ep.epNum}|sub") {
                this.episode = ep.epNum.toInt()
                this.name = ep.name ?: "Episode ${ep.epNum}"
                this.description = ep.desc
                this.posterUrl = if (ep.img?.startsWith("/") == true) "$mainUrl${ep.img}" else ep.img
            }
        }

        val dubEpisodes = if (hasDub) {
            eps.map { ep ->
                newEpisode("animetsu|$animeId|${ep.epNum}|dub") {
                    this.episode = ep.epNum.toInt()
                    this.name = ep.name ?: "Episode ${ep.epNum}"
                    this.description = ep.desc
                    this.posterUrl = if (ep.img?.startsWith("/") == true) "$mainUrl${ep.img}" else ep.img
                }
            }
        } else {
            emptyList()
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
        if (!data.startsWith("animetsu|")) return false
        val parts = data.split("|")
        val animeId = parts[1]
        val epNum = parts[2]
        val sourceType = parts[3] // "sub" or "dub"

        val serversText = apiGet("$apiBase/anime/servers/$animeId/$epNum")
        val servers = parseJson<List<ServerItem>>(serversText)

        var found = false
        for (server in servers) {
            try {
                val oppaiText = apiGet("$apiBase/anime/oppai/$animeId/$epNum?server=${server.id}&source_type=$sourceType")
                val oppaiRes = parseJson<OppaiResponse>(oppaiText)
                
                val sources = oppaiRes.sources ?: continue
                for (source in sources) {
                    val url = source.url ?: continue
                    val finalUrl = if (url.startsWith("http")) {
                        url
                    } else if (source.needProxy == true) {
                        "$proxyBase${if (url.startsWith("/")) "" else "/"}$url"
                    } else {
                        "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
                    }

                    val isM3u8 = source.type?.contains("mpegurl", ignoreCase = true) == true || finalUrl.contains(".m3u8")
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback(
                        newExtractorLink(
                            source = "Animetsu - ${server.id}",
                            name = "Animetsu - ${server.id} (${source.quality ?: "master"})",
                            url = finalUrl,
                            type = linkType
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl
                            )
                        }
                    )
                    found = true
                }

                val subs = oppaiRes.subs ?: continue
                for (sub in subs) {
                    val subUrl = sub.url ?: continue
                    subtitleCallback(
                        SubtitleFile(
                            sub.lang ?: "English",
                            subUrl
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore error for single server, try other servers
            }
        }
        return found
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse {
        val name = title?.english ?: title?.romaji ?: title?.native ?: "Unknown"
        val poster = coverImage?.large ?: coverImage?.medium ?: coverImage?.small
        return newAnimeSearchResponse(name, id, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // JSON parsing models
    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null
    )

    data class CoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null,
        @JsonProperty("small") val small: String? = null
    )

    data class AnimeItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("cover_image") val coverImage: CoverImage? = null
    )

    data class PaginatedResponse(
        @JsonProperty("results") val results: List<AnimeItem>? = null,
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("last_page") val lastPage: Int? = null
    )

    data class HomeResponse(
        @JsonProperty("seasonal") val seasonal: List<AnimeItem>? = null,
        @JsonProperty("trending") val trending: List<AnimeItem>? = null,
        @JsonProperty("popular") val popular: List<AnimeItem>? = null,
        @JsonProperty("top") val top: List<AnimeItem>? = null,
        @JsonProperty("upcoming") val upcoming: List<AnimeItem>? = null
    )

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

    data class EpisodeItem(
        @JsonProperty("ep_num") val epNum: Double,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("img") val img: String? = null
    )

    data class ServerItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("default") val default: Boolean = false,
        @JsonProperty("tip") val tip: String? = null
    )

    data class OppaiSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("need_proxy") val needProxy: Boolean? = null
    )

    data class OppaiSub(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    data class OppaiResponse(
        @JsonProperty("sources") val sources: List<OppaiSource>? = null,
        @JsonProperty("subs") val subs: List<OppaiSub>? = null
    )
}
