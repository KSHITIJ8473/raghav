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
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
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

    // Providers that commonly have BOTH sub and dub first
    private val providerOrder = listOf("bonk", "ally", "bee", "hop", "moo", "kiwi", "pewe")

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
            val providers = episodesData.providers ?: emptyMap()

            // ── Build Sub episodes from best available provider ──
            var subProvider: String? = null
            for (provName in providerOrder) {
                val prov = providers[provName] ?: continue
                val subs = prov.episodes?.sub
                if (!subs.isNullOrEmpty()) {
                    subProvider = provName
                    subs.forEach { ep ->
                        val epId = ep.id ?: return@forEach
                        val epNum = ep.number ?: return@forEach
                        // Data format: anilistId|provider|episodeId|audioType|episodeNumber
                        val data = "$anilistId|$provName|$epId|sub|$epNum"
                        subEpisodes.add(newEpisode(data) {
                            this.name = ep.title ?: "Episode $epNum"
                            this.episode = epNum
                            this.description = ep.description
                            this.posterUrl = ep.image
                        })
                    }
                    break
                }
            }

            // ── Build Dub episodes - find a provider that actually has dub ──
            for (provName in providerOrder) {
                val prov = providers[provName] ?: continue
                val dubs = prov.episodes?.dub
                if (!dubs.isNullOrEmpty()) {
                    dubs.forEach { ep ->
                        val epId = ep.id ?: return@forEach
                        val epNum = ep.number ?: return@forEach
                        // Data format: anilistId|provider|episodeId|audioType|episodeNumber
                        val data = "$anilistId|$provName|$epId|dub|$epNum"
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

        } catch (e: Exception) {
            // If pipe fails, create placeholder episodes from AniList count
            val totalEps = media.episodes ?: 0
            for (i in 1..totalEps) {
                subEpisodes.add(newEpisode("$anilistId|bonk|unknown|sub|$i") {
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
        // Data format: anilistId|provider|episodeId|audioType|episodeNumber
        val parts = data.split("|")
        if (parts.size < 4) return false

        val anilistId = parts[0].toIntOrNull() ?: return false
        val provider = parts[1]
        val episodeId = parts[2]
        val audioType = parts[3]
        val episodeNumber = parts.getOrNull(4)?.toIntOrNull()

        // Build list of providers to try: primary first, then fallbacks
        val providersToTry = mutableListOf(provider)
        providerOrder.forEach { if (it != provider) providersToTry.add(it) }

        var foundSources = false

        for (prov in providersToTry) {
            try {
                // Get the right episode ID for this provider
                val actualEpisodeId = if (prov == provider) {
                    episodeId
                } else {
                    // Fallback: look up this episode in another provider
                    getEpisodeIdForProvider(anilistId, prov, audioType, episodeNumber)
                        ?: continue
                }

                val sourcesJson = miruroPipeRequest(
                    mainUrl,
                    "sources",
                    mapOf("episodeId" to actualEpisodeId, "provider" to prov)
                )
                val sourcesData = parseJson<MiruroSourcesResponse>(sourcesJson)
                val streams = sourcesData.streams ?: emptyList()

                // Filter for HLS streams with direct M3U8 links
                val hlsStreams = streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }

                if (hlsStreams.isNotEmpty()) {
                    for (stream in hlsStreams) {
                        val m3u8Url = stream.url ?: continue
                        val referer = stream.referer ?: "$mainUrl/"
                        val fansubLabel = if (!stream.fansub.isNullOrEmpty()) " [${stream.fansub}]" else ""
                        val qualityLabel = stream.quality ?: "Auto"
                        val sourceName = "Miruro - $prov${fansubLabel}"

                        // Use generateM3u8 for proper HLS handling (fixes buffering on seek)
                        try {
                            generateM3u8(
                                sourceName,
                                m3u8Url,
                                referer
                            ).forEach(callback)
                        } catch (e: Exception) {
                            // Fallback: add as raw ExtractorLink if generateM3u8 fails
                            val quality = qualityFromString(stream.quality)
                            callback.invoke(
                                newExtractorLink(
                                    source = "Miruro",
                                    name = "$sourceName ($qualityLabel)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("Referer" to referer)
                                }
                            )
                        }
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

                if (foundSources) break // Stop trying other providers

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
