package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class Anivexa : MainAPI() {
    override var mainUrl = "https://anivexa.vercel.app"
    override var name = "Anivexa"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "Popular Anime",
        "upcoming" to "Upcoming Anime",
        "top" to "Top Rated All Time"
    )

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        val requestData = mapOf(
            "query" to query,
            "variables" to variables
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return app.post(
            "https://graphql.anilist.co",
            requestBody = requestData,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        ).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = when (request.data) {
            "trending" -> TRENDING_QUERY
            "popular" -> POPULAR_QUERY
            "upcoming" -> UPCOMING_QUERY
            "top" -> TOP_QUERY
            else -> TRENDING_QUERY
        }

        val variables = mapOf("page" to page)
        val responseText = anilistQuery(query, variables)
        val response = parseJson<AnivexaAniListSearchResponse>(responseText)
        val mediaList = response.data?.page?.media ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            
            newAnimeSearchResponse(title, "$mainUrl/anime.html?id=$id", TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf("search" to query, "page" to 1)
        val responseText = anilistQuery(SEARCH_MUTATION, variables)
        val response = parseJson<AnivexaAniListSearchResponse>(responseText)
        val mediaList = response.data?.page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            
            newAnimeSearchResponse(title, "$mainUrl/anime.html?id=$id", TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = Regex("""id=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val variables = mapOf("id" to animeId)
        val responseText = anilistQuery(INFO_QUERY, variables)
        val response = parseJson<AnivexaAniListDetailsResponse>(responseText)
        val media = response.data?.media ?: return null

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

        val tvType = when (media.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        
        val totalEps = media.episodes ?: 24
        val count = if (totalEps > 0) totalEps else 24

        if (media.format == "MOVIE") {
            subEpisodes.add(newEpisode("$animeId|1|sub") {
                this.name = title
                this.episode = 1
            })
            dubEpisodes.add(newEpisode("$animeId|1|dub") {
                this.name = title
                this.episode = 1
            })
        } else {
            for (i in 1..count) {
                subEpisodes.add(newEpisode("$animeId|$i|sub") {
                    this.name = "Episode $i"
                    this.episode = i
                })
                dubEpisodes.add(newEpisode("$animeId|$i|dub") {
                    this.name = "Episode $i"
                    this.episode = i
                })
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            if (animeScore != null) this.score = Score.from10((animeScore / 10.0).toString())
            this.showStatus = showStatus
            addAniListId(animeId)
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
        val parts = data.split("|")
        if (parts.size < 3) return false
        
        val animeId = parts[0]
        val epNum = parts[1]
        val audioType = parts[2] // "sub" or "dub"

        val watchTargetUrl = "$mainUrl/anime.html?id=$animeId&audio=$audioType&ep=$epNum"
        var linksHarvested = false

        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""^intercept:.*"""),
                additionalUrls = emptyList(),
                script = """
                    // 1. Hijack the SvelteKit API Fetch to instantly grab the server JSON!
                    const originalFetch = window.fetch;
                    window.fetch = async function() {
                        const response = await originalFetch.apply(this, arguments);
                        const url = arguments[0] || '';
                        if (typeof url === 'string') {
                            const clone = response.clone();
                            clone.text().then(text => {
                                // If the API responds with server links, redirect to Kotlin!
                                if (text.includes('.m3u8') || text.includes('megaplay') || text.includes('vidwish') || text.includes('tryembed') || text.includes('vidnest')) {
                                    window.location.href = 'intercept:api:' + encodeURIComponent(text);
                                }
                            }).catch(e => {});
                        }
                        return response;
                    };

                    // 2. Fallback: Search the DOM for injected server iframes/buttons
                    let checkInterval = setInterval(function() {
                        let serverLinks = [];
                        document.querySelectorAll('a, button, div, iframe').forEach(el => {
                            let url = el.getAttribute('data-url') || el.getAttribute('href') || el.getAttribute('data-src') || el.src;
                            if (url && typeof url === 'string' && url.startsWith('http') && !url.includes('anivexa.vercel.app')) {
                                if (url.includes('megaplay') || url.includes('vidwish') || url.includes('tryembed') || url.includes('vidnest') || url.includes('dropfile') || url.includes('nightslayer') || url.includes('abyss') || url.includes('ryzex')) {
                                    serverLinks.push(url);
                                }
                            }
                        });
                        
                        if (serverLinks.length > 0) {
                            clearInterval(checkInterval);
                            window.location.href = 'intercept:servers:' + encodeURIComponent(JSON.stringify(serverLinks));
                        }
                    }, 500);
                """.trimIndent(),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            val resolved = app.get(watchTargetUrl, referer = mainUrl, interceptor = resolver).url
            
            // Decrypt the intercepted API call
            if (resolved.startsWith("intercept:servers:")) {
                val jsonArray = java.net.URLDecoder.decode(resolved.removePrefix("intercept:servers:"), "UTF-8")
                val urls = parseJson<List<String>>(jsonArray)
                urls.forEach { url ->
                    if (url.contains("megaplay.buzz")) AnivexaMegaPlay().getUrl(url, watchTargetUrl, subtitleCallback, callback)
                    else if (url.contains("vidwish.live")) AnivexaVidWish().getUrl(url, watchTargetUrl, subtitleCallback, callback)
                    else if (url.contains("vidnest.fun")) AnivexaVidNest().getUrl(url, watchTargetUrl, subtitleCallback, callback)
                    else loadExtractor(url, watchTargetUrl, subtitleCallback, callback)
                    linksHarvested = true
                }
            } 
            else if (resolved.startsWith("intercept:api:")) {
                val jsonText = java.net.URLDecoder.decode(resolved.removePrefix("intercept:api:"), "UTF-8")
                val urlRegex = Regex("""(https?://[^\s"'\\]+)""")
                urlRegex.findAll(jsonText).forEach { match ->
                    val url = match.value.replace("\\/", "/")
                    if (url.contains(".m3u8", ignoreCase = true)) {
                        M3u8Helper.generateM3u8("Anivexa Direct ($audioType)", url, watchTargetUrl).forEach(callback)
                        linksHarvested = true
                    } else if (url.contains("megaplay.buzz")) {
                        AnivexaMegaPlay().getUrl(url, watchTargetUrl, subtitleCallback, callback)
                        linksHarvested = true
                    } else if (url.contains("vidwish.live")) {
                        AnivexaVidWish().getUrl(url, watchTargetUrl, subtitleCallback, callback)
                        linksHarvested = true
                    } else if (url.contains("vidnest.fun")) {
                        AnivexaVidNest().getUrl(url, watchTargetUrl, subtitleCallback, callback)
                        linksHarvested = true
                    } else if (url.contains("tryembed") || url.contains("dropfile") || url.contains("nightslayer")) {
                        loadExtractor(url, watchTargetUrl, subtitleCallback, callback)
                        linksHarvested = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Anivexa", "WebView extraction failed: ${e.message}")
        }

        return linksHarvested
    }

    companion object {
        private val SEARCH_MUTATION = """
            query (${'$'}search: String, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(search: ${'$'}search, type: ANIME, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val INFO_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id format title { romaji english } description(asHtml: false)
                coverImage { extraLarge large } bannerImage averageScore
                seasonYear episodes status genres
              }
            }
        """.trimIndent()

        private val TRENDING_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val POPULAR_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val UPCOMING_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, status: NOT_YET_RELEASED, sort: POPULARITY_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val TOP_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: SCORE_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()
    }
}

// ── CUSTOM EMBED EXTRACTORS TO BYPASS CLOUDFLARE ──
open class AnivexaMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = emptyList(),
                script = """document.querySelector('.jw-icon-display, .vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 15_000L
            )
            val resolved = app.get(url, referer = referer ?: "https://anivexa.vercel.app/", interceptor = resolver).url
            if (resolved.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, resolved, mainUrl).forEach(callback)
            }
        } catch (e: Exception) {}
    }
}

class AnivexaVidWish : AnivexaMegaPlay("VidWish") {
    override val mainUrl = "https://vidwish.live"
}

class AnivexaVidNest : AnivexaMegaPlay("VidNest") {
    override val mainUrl = "https://vidnest.fun"
}

// ── DATA CLASSES FOR JSON METADATA ──
data class AnivexaAniListSearchResponse(
    @JsonProperty("data") val data: AnivexaAniListSearchData? = null
)
data class AnivexaAniListSearchData(
    @JsonProperty("Page") val page: AnivexaAniListMediaPageContainer? = null
)
data class AnivexaAniListMediaPageContainer(
    @JsonProperty("media") val media: List<AnivexaAniListMedia>? = null
)

data class AnivexaAniListDetailsResponse(
    @JsonProperty("data") val data: AnivexaAniListDetailsData? = null
)
data class AnivexaAniListDetailsData(
    @JsonProperty("Media") val media: AnivexaAniListMedia? = null
)

data class AnivexaAniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: AnivexaAniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AnivexaAniListCoverImage? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null
)

data class AnivexaAniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null
)

data class AnivexaAniListCoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null
)
