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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

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
        
        val nextAiring = media.nextAiringEpisode?.episode
        val totalEps = media.episodes ?: (if (nextAiring != null) nextAiring - 1 else 24)
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
        val audioType = parts[2]

        val watchTargetUrl = "$mainUrl/anime.html?id=$animeId&audio=$audioType&ep=$epNum"
        var linksHarvested = false

        try {
            // We use a valid HTTP URL format for the intercept so Svelte doesn't block it!
            val resolver = WebViewResolver(
                interceptUrl = Regex("""anivexa\.vercel\.app/intercept\?url="""),
                additionalUrls = emptyList(),
                script = """
                    (function() {
                        function interceptStream(url) {
                            if (typeof url !== 'string') return;
                            if (url.includes('workers.dev/p/') || 
                                url.includes('megaplay') || 
                                url.includes('vidwish') || 
                                url.includes('vidnest') || 
                                url.includes('.m3u8')) {
                                
                                // Send a background fetch request so CloudStream catches it instantly!
                                fetch('https://anivexa.vercel.app/intercept?url=' + encodeURIComponent(url)).catch(e=>{});
                                
                                // Also attempt navigation just in case
                                window.location.href = 'https://anivexa.vercel.app/intercept?url=' + encodeURIComponent(url);
                            }
                        }

                        // 1. Intercept internal XMLHttpRequest fetches
                        const origOpen = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            interceptStream(url);
                            return origOpen.apply(this, arguments);
                        };

                        // 2. Intercept internal Fetch API
                        const origFetch = window.fetch;
                        window.fetch = async function(...args) {
                            interceptStream(args[0]);
                            return origFetch.apply(this, args);
                        };

                        // 3. Constantly scan the DOM for embedded iframes and force the player to start
                        let checks = 0;
                        let checkInterval = setInterval(function() {
                            checks++;
                            if (checks > 40) clearInterval(checkInterval);
                            
                            document.querySelectorAll('iframe').forEach(ifr => {
                                if (ifr.src && !ifr.src.includes('anivexa') && !ifr.src.includes('google')) {
                                    interceptStream(ifr.src);
                                }
                            });
                            
                            document.querySelectorAll('video').forEach(v => {
                                v.muted = true; 
                                v.play().catch(e => {});
                            });
                            
                            let btn = document.querySelector('.vds-play-button, .play-button, button[aria-label="Play"]');
                            if(btn) btn.click();
                        }, 500);
                    })();
                """.trimIndent(),
                useOkhttp = false,
                timeout = 25_000L
            )
            
            val resolvedUrl = app.get(watchTargetUrl, referer = mainUrl, interceptor = resolver).url
            
            if (resolvedUrl.contains("/intercept?url=")) {
                val encodedUrl = resolvedUrl.substringAfter("/intercept?url=")
                val streamUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                
                val reqHeaders = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
                )

                // If it's the Cloudflare worker proxy, natively parse the m3u8 playlist it generates
                if (streamUrl.contains("workers.dev/p/") || streamUrl.contains(".m3u8")) {
                    val m3u8Links = M3u8Helper.generateM3u8("Anivexa Server", streamUrl, mainUrl, headers = reqHeaders)
                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(
                                source = "Anivexa Proxy",
                                name = "Anivexa Proxy ($audioType)",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = reqHeaders
                            }
                        )
                    }
                    linksHarvested = true
                } else if (streamUrl.contains("megaplay", ignoreCase = true)) {
                    AnivexaMegaPlay().getUrl(streamUrl, watchTargetUrl, subtitleCallback, callback)
                    linksHarvested = true
                } else if (streamUrl.contains("vidwish", ignoreCase = true)) {
                    AnivexaVidWish().getUrl(streamUrl, watchTargetUrl, subtitleCallback, callback)
                    linksHarvested = true
                } else if (streamUrl.contains("vidnest", ignoreCase = true)) {
                    AnivexaVidNest().getUrl(streamUrl, watchTargetUrl, subtitleCallback, callback)
                    linksHarvested = true
                } else {
                    loadExtractor(streamUrl, watchTargetUrl, subtitleCallback, callback)
                    linksHarvested = true
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
                nextAiringEpisode { episode }
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
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AnivexaNextAiring? = null
)

data class AnivexaNextAiring(
    @JsonProperty("episode") val episode: Int? = null
)

data class AnivexaAniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null
)

data class AnivexaAniListCoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null
)
