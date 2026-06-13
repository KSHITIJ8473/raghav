package com.laddu100

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.Score

class Miruro : MainAPI() {
    override var mainUrl = "https://www.miruro.ru"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Provider priority order - try these providers for streaming sources
    private val providerOrder = listOf("kiwi", "bonk", "ally", "moo", "bee", "hop", "pewe")

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Recently Updated",
    )

    // ─── Home Page ──────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val query = when (request.data) {
            "TRENDING" -> TRENDING_QUERY
            "POPULAR" -> POPULAR_QUERY
            "RECENT" -> RECENT_QUERY
            else -> TRENDING_QUERY
        }

        val variables = mapOf("page" to page, "perPage" to 20)
        val responseText = anilistQuery(query, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            val url = "$mainUrl/info/$id/${toSlug(title)}"

            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(
                    dubExist = true,
                    subExist = true,
                    dubEpisodes = media.episodes,
                    subEpisodes = media.episodes
                )
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
        val responseText = anilistQuery(SEARCH_QUERY, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            val url = "$mainUrl/info/$id/${toSlug(title)}"

            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(
                    dubExist = true,
                    subExist = true,
                    dubEpisodes = media.episodes,
                    subEpisodes = media.episodes
                )
            }
        }
    }

    // ─── Load (Anime Details + Episodes) ────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        // Extract AniList ID from URL: /info/{id}/{slug}
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        // Fetch anime metadata from AniList
        val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
        val infoResponse = parseJson<AniListResponse>(infoText)
        val media = infoResponse.data?.Media ?: return null

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")  // Strip HTML tags
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore // AniList uses 0-100
        val studio = media.studios?.nodes?.firstOrNull { it.isAnimationStudio == true }?.name

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

        // Fetch episodes from Miruro pipe
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        try {
            val episodesJson = miruroPipeRequest(mainUrl, "episodes", mapOf("anilistId" to anilistId))
            val episodesData = parseJson<MiruroEpisodesResponse>(episodesJson)

            // Find best provider (one with most episodes)
            val providers = episodesData.providers ?: emptyMap()
            val bestProvider = providerOrder.firstOrNull { providers.containsKey(it) }
                ?: providers.keys.firstOrNull()

            if (bestProvider != null) {
                val providerData = providers[bestProvider]
                val subEps = providerData?.episodes?.sub ?: emptyList()
                val dubEps = providerData?.episodes?.dub ?: emptyList()

                // Build sub episodes
                subEps.forEach { ep ->
                    val epId = ep.id ?: return@forEach
                    val epNum = ep.number ?: return@forEach

                    // Data format: anilistId|provider|episodeId|sub
                    val data = "$anilistId|$bestProvider|$epId|sub"

                    subEpisodes.add(newEpisode(data) {
                        this.name = ep.title ?: "Episode $epNum"
                        this.episode = epNum
                        this.description = ep.description
                        this.posterUrl = ep.image
                    })
                }

                // Build dub episodes
                dubEps.forEach { ep ->
                    val epId = ep.id ?: return@forEach
                    val epNum = ep.number ?: return@forEach

                    val data = "$anilistId|$bestProvider|$epId|dub"

                    dubEpisodes.add(newEpisode(data) {
                        this.name = ep.title ?: "Episode $epNum"
                        this.episode = epNum
                        this.description = ep.description
                        this.posterUrl = ep.image
                    })
                }

                // If best provider doesn't have dub, check others
                if (dubEpisodes.isEmpty()) {
                    for (provName in providerOrder) {
                        val prov = providers[provName] ?: continue
                        val dubs = prov.episodes?.dub ?: continue
                        if (dubs.isNotEmpty()) {
                            dubs.forEach { ep ->
                                val epId = ep.id ?: return@forEach
                                val epNum = ep.number ?: return@forEach
                                val data = "$anilistId|$provName|$epId|dub"
                                dubEpisodes.add(newEpisode(data) {
                                    this.name = ep.title ?: "Episode $epNum"
                                    this.episode = epNum
                                    this.description = ep.description
                                    this.posterUrl = ep.image
                                })
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // If pipe fails, create placeholder episodes from AniList count
            val totalEps = media.episodes ?: 0
            for (i in 1..totalEps) {
                subEpisodes.add(newEpisode("$anilistId|kiwi|unknown|sub|$i") {
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
            if (animeScore != null) this.score = Score.from10((animeScore / 10).toString())
            this.showStatus = showStatus
            addAniListId(anilistId)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ─── Load Links (Get Streaming URLs) ────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data format: anilistId|provider|episodeId|audioType
        val parts = data.split("|")
        if (parts.size < 4) return false

        val anilistId = parts[0]
        val provider = parts[1]
        val episodeId = parts[2]
        val audioType = parts[3]

        // Try to get sources from primary provider
        var foundSources = false
        val providersToTry = mutableListOf(provider)
        // Add fallback providers
        providerOrder.forEach { if (it != provider) providersToTry.add(it) }

        for (prov in providersToTry) {
            try {
                // If we're trying a different provider, we need to fetch its episode ID
                val actualEpisodeId = if (prov == provider) {
                    episodeId
                } else {
                    getEpisodeIdForProvider(anilistId.toInt(), prov, audioType, parts.getOrNull(4)?.toIntOrNull())
                        ?: continue
                }

                val sourcesJson = miruroPipeRequest(
                    mainUrl,
                    "sources",
                    mapOf("episodeId" to actualEpisodeId, "provider" to prov)
                )
                val sourcesData = parseJson<MiruroSourcesResponse>(sourcesJson)
                val streams = sourcesData.streams ?: emptyList()

                // Filter for HLS streams only (direct M3U8 links)
                val hlsStreams = streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }

                if (hlsStreams.isNotEmpty()) {
                    hlsStreams.forEach { stream ->
                        val quality = qualityFromString(stream.quality)
                        val qualityLabel = stream.quality ?: "Auto"
                        val fansubLabel = if (!stream.fansub.isNullOrEmpty()) " [${stream.fansub}]" else ""
                        val sourceName = "Miruro - $prov${fansubLabel} ($qualityLabel)"

                        callback.invoke(
                            newExtractorLink(
                                source = "Miruro",
                                name = sourceName,
                                url = stream.url!!,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = quality
                                this.headers = mapOf("Referer" to (stream.referer ?: "$mainUrl/"))
                            }
                        )
                    }
                    foundSources = true
                }

                // Add subtitles
                sourcesData.subtitles?.forEach { sub ->
                    if (!sub.url.isNullOrEmpty()) {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                sub.lang ?: "English",
                                sub.url
                            )
                        )
                    }
                }

                if (foundSources) break // Stop trying other providers if we found sources

            } catch (e: Exception) {
                continue // Try next provider
            }
        }

        return foundSources
    }

    // ─── Helper Functions ───────────────────────────────────────────────────

    private suspend fun getEpisodeIdForProvider(
        anilistId: Int,
        provider: String,
        audioType: String,
        episodeNumber: Int?
    ): String? {
        if (episodeNumber == null) return null
        try {
            val episodesJson = miruroPipeRequest(mainUrl, "episodes", mapOf("anilistId" to anilistId))
            val episodesData = parseJson<MiruroEpisodesResponse>(episodesJson)
            val providerData = episodesData.providers?.get(provider) ?: return null
            val episodes = if (audioType == "dub") {
                providerData.episodes?.dub
            } else {
                providerData.episodes?.sub
            } ?: return null

            return episodes.firstOrNull { it.number == episodeNumber }?.id
        } catch (e: Exception) {
            return null
        }
    }

    private fun toSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }
}
