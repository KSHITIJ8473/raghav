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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.Score
import com.lagradost.api.Log

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

    // Provider order: fastest first, then embed-supported, then others
    private val providerOrder = listOf(
        "anikoto", "animepahe", "anidbapp", "yuki", "anineko",
        "allmanga", "reanime", "mimi", "shiro", "wave",
        "mochi", "animegg", "zen", "hellforest"
    )

    // Display names for providers
    private val providerDisplayNames = mapOf(
        "anidbapp" to "Nexus",
        "anikoto" to "Solaris",
        "animepahe" to "Astra",
        "yuki" to "Frost",
        "anineko" to "Lynx",
        "allmanga" to "Titan",
        "reanime" to "Orion",
        "mimi" to "Lunar",
        "shiro" to "Halo",
        "wave" to "Tide",
        "mochi" to "Eclipse",
        "animegg" to "Prism",
        "zen" to "Aurora",
        "hellforest" to "Hell Forest"
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = when (request.data) {
            "TRENDING" -> "/api/v/trending?page=$page&per_page=20"
            "POPULAR" -> "/api/v/popular?page=$page&per_page=20"
            "RECENT" -> "/api/v/airing?page=$page&per_page=20"
            else -> "/api/v/trending?page=$page&per_page=20"
        }

        val responseText = AnivexaApi.apiGet(endpoint)
        val response = parseJson<AnivexaListResponse>(responseText)
        val mediaList = response.data ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji
                ?: media.title?.userPreferred ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
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

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "/api/v/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&per_page=20"
        val responseText = AnivexaApi.apiGet(endpoint)
        val response = parseJson<AnivexaListResponse>(responseText)
        val mediaList = response.data ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji
                ?: media.title?.userPreferred ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
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

    override suspend fun load(url: String): LoadResponse? {
        // Extract anilist ID from URL: /anime/101922
        val anilistId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        // Get anime info
        val infoText = AnivexaApi.apiGet("/api/v/anime/$anilistId")
        val infoResponse = parseJson<AnivexaInfoResponse>(infoText)
        val media = infoResponse.data ?: return null

        val title = media.title?.english ?: media.title?.romaji
            ?: media.title?.userPreferred ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

        val tvType = when (media.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        var providers: Map<String, AnivexaProvider>? = null
        var mappings: AnivexaMappings? = null

        try {
            val episodesText = AnivexaApi.apiGet("/api/anime/episodes/$anilistId")
            val episodesData = parseJson<AnivexaEpisodesResponse>(episodesText)
            providers = episodesData.providers
            mappings = episodesData.mappings
            Log.i("Anivexa", "Episodes API responded for $title: providers=${providers?.size ?: 0}, mappings=${mappings?.episodes ?: 0}")
        } catch (e: Exception) {
            Log.e("Anivexa", "Failed to load episodes for $title (id=$anilistId): ${e.message}")
        }

        if (providers != null && providers!!.isNotEmpty()) {
            // ── Sub episodes ──
            // Find provider with most sub episodes to use as the episode list
            var bestSubProvider: String? = null
            var bestSubCount = 0
            for (provName in providerOrder) {
                val count = providers!![provName]?.episodes?.sub?.size ?: 0
                if (count > bestSubCount) {
                    bestSubCount = count
                    bestSubProvider = provName
                }
            }

            if (bestSubProvider != null) {
                val subList = providers!![bestSubProvider]!!.episodes!!.sub ?: emptyList()
                subList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    subEpisodes.add(newEpisode("$anilistId|sub|$epNum") {
                        this.name = ep.title ?: "Episode $epNum"
                        this.episode = epNum
                        this.description = ep.description
                        this.posterUrl = ep.image
                    })
                }
            }

            // ── Dub episodes ──
            var bestDubProvider: String? = null
            var bestDubCount = 0
            for (provName in providerOrder) {
                val count = providers!![provName]?.episodes?.dub?.size ?: 0
                if (count > bestDubCount) {
                    bestDubCount = count
                    bestDubProvider = provName
                }
            }

            if (bestDubProvider != null) {
                val dubList = providers!![bestDubProvider]!!.episodes!!.dub ?: emptyList()
                dubList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    dubEpisodes.add(newEpisode("$anilistId|dub|$epNum") {
                        this.name = ep.title ?: "Episode $epNum"
                        this.episode = epNum
                        this.description = ep.description
                        this.posterUrl = ep.image
                    })
                }
            }
        }

        // ── Fallback: Generate episodes from total count if API failed or returned empty ──
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            val totalEpisodes = media.episodes ?: mappings?.episodes ?: 0
            if (totalEpisodes > 0 && totalEpisodes <= 2000) {
                Log.i("Anivexa", "Using fallback: generating $totalEpisodes episodes for $title (id=$anilistId)")
                for (epNum in 1..totalEpisodes) {
                    subEpisodes.add(newEpisode("$anilistId|sub|$epNum") {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    })
                    dubEpisodes.add(newEpisode("$anilistId|dub|$epNum") {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    })
                }
            } else if (totalEpisodes > 2000) {
                Log.w("Anivexa", "Too many episodes ($totalEpisodes) for $title - skipping fallback generation")
            } else {
                Log.e("Anivexa", "No episodes found and no total count available for $title")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Format: anilistId|sub|epNum or anilistId|dub|epNum
        val parts = data.split("|")
        if (parts.size < 3) return false

        val anilistId = parts[0].toIntOrNull() ?: return false
        val audioType = parts[1] // "sub" or "dub"
        val epNum = parts[2].toIntOrNull() ?: return false

        Log.i("Anivexa", "loadLinks: anilistId=$anilistId, audioType=$audioType, epNum=$epNum")

        var foundAnySources = false
        val seenUrls = mutableSetOf<String>()

        // Try to get episode IDs from the episodes API (fast lookup by epNum)
        var providerEpisodeIds = mutableMapOf<String, String>()

        try {
            val episodesText = AnivexaApi.apiGet("/api/anime/episodes/$anilistId")
            val episodesData = parseJson<AnivexaEpisodesResponse>(episodesText)
            val providers = episodesData.providers ?: emptyMap()

            for (provName in providerOrder) {
                val provData = providers[provName]?.episodes ?: continue
                val epList = if (audioType == "sub") provData.sub else provData.dub
                val ep = epList?.firstOrNull { it.number == epNum } ?: continue
                val epId = ep.id ?: continue
                providerEpisodeIds[provName] = epId
            }
            Log.i("Anivexa", "Found ${providerEpisodeIds.size} providers with episode $epNum")
        } catch (e: Exception) {
            Log.w("Anivexa", "Could not fetch episodes list, will try direct URL patterns: ${e.message}")
        }

        // Try each provider in priority order
        for (provName in providerOrder) {
            val displayName = providerDisplayNames[provName] ?: provName

            // Build list of watch URL patterns to try
            val watchUrlsToTry = mutableListOf<String>()

            // Pattern 1: Use the episode ID from the episodes API (if available)
            if (providerEpisodeIds.containsKey(provName)) {
                val epId = providerEpisodeIds[provName]!!
                // The episode ID may be a full path like "watch/animepahe/101922/sub/animepahe-1"
                watchUrlsToTry.add("/api/anime/$epId")
                // Or it might be just the slug like "animepahe-1"
                if (!epId.startsWith("watch/")) {
                    watchUrlsToTry.add("/api/anime/watch/$provName/$anilistId/$audioType/$epId")
                }
            }

            // Pattern 2: Construct URL with provider-epNum format
            watchUrlsToTry.add("/api/anime/watch/$provName/$anilistId/$audioType/$provName-$epNum")
            // Pattern 3: Construct URL with just epNum
            watchUrlsToTry.add("/api/anime/watch/$provName/$anilistId/$audioType/$epNum")

            for (watchPath in watchUrlsToTry) {
                try {
                    Log.i("Anivexa", "Trying $provName: $watchPath")
                    val watchJson = AnivexaApi.apiGet(watchPath)
                    val watchData = parseJson<AnivexaWatchResponse>(watchJson)

                    // Handle two response formats:
                    // Format 1: {"ssub": {"streams": [...]}, "sdub": {...}}
                    // Format 2: {"streams": [...], ...}
                    val streams: List<AnivexaStream>
                    val subtitles: List<AnivexaSubtitle>

                    if (audioType == "sub" && watchData.ssub != null) {
                        streams = watchData.ssub.streams ?: emptyList()
                        subtitles = watchData.ssub.subtitles ?: emptyList()
                    } else if (audioType == "dub" && watchData.sdub != null) {
                        streams = watchData.sdub.streams ?: emptyList()
                        subtitles = watchData.sdub.subtitles ?: emptyList()
                    } else if (watchData.streams != null) {
                        streams = watchData.streams
                        subtitles = watchData.subtitles ?: emptyList()
                    } else {
                        val subData = watchData.ssub
                        val dubData = watchData.sdub
                        val allSub = subData?.streams ?: emptyList()
                        val allDub = dubData?.streams ?: emptyList()
                        streams = if (audioType == "sub") allSub.ifEmpty { allDub }
                                  else allDub.ifEmpty { allSub }
                        subtitles = subData?.subtitles ?: dubData?.subtitles ?: emptyList()
                    }

                    if (streams.isEmpty()) {
                        Log.d("Anivexa", "No streams for $provName at $watchPath")
                        continue
                    }

                    Log.i("Anivexa", "Found ${streams.size} streams for $provName")

                    // HLS streams
                    for (stream in streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }) {
                        val streamUrl = stream.url ?: continue
                        if (!seenUrls.add(streamUrl)) continue

                        val referer = stream.referer ?: "$mainUrl/"
                        val serverName = stream.server ?: displayName

                        callback.invoke(
                            newExtractorLink(
                                source = "Anivexa",
                                name = "$displayName - $serverName",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = getQualityFromPriority(stream.priority)
                                this.headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                )
                            }
                        )
                        foundAnySources = true
                    }

                    // MP4 streams
                    for (stream in streams.filter { it.type == "mp4" && !it.url.isNullOrEmpty() }) {
                        val streamUrl = stream.url ?: continue
                        if (!seenUrls.add(streamUrl)) continue

                        val referer = stream.referer ?: "$mainUrl/"
                        val serverName = stream.server ?: displayName

                        callback.invoke(
                            newExtractorLink(
                                source = "Anivexa",
                                name = "$displayName - $serverName (MP4)",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromPriority(stream.priority)
                                this.headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                )
                            }
                        )
                        foundAnySources = true
                    }

                    // Embed streams
                    for (stream in streams.filter { it.type == "embed" && !it.url.isNullOrEmpty() }) {
                        val embedUrl = stream.url ?: continue
                        if (!seenUrls.add(embedUrl)) continue

                        val referer = stream.referer ?: "$mainUrl/"
                        try {
                            if (embedUrl.contains("megaplay.buzz")) {
                                AnivexaMegaPlay().getUrl(embedUrl, referer, subtitleCallback, callback)
                                foundAnySources = true
                            } else if (embedUrl.contains("vidwish.live")) {
                                AnivexaVidWish().getUrl(embedUrl, referer, subtitleCallback, callback)
                                foundAnySources = true
                            } else if (embedUrl.contains("vidtube.site")) {
                                AnivexaVidTube().getUrl(embedUrl, referer, subtitleCallback, callback)
                                foundAnySources = true
                            } else {
                                try {
                                    loadExtractor(embedUrl, referer, subtitleCallback, callback)
                                    foundAnySources = true
                                } catch (_: Exception) {
                                    AnivexaWebView(provName, embedUrl).getUrl(
                                        embedUrl, referer, subtitleCallback, callback
                                    )
                                    foundAnySources = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Anivexa", "Embed extraction failed for $provName: ${e.message}")
                        }
                    }

                    // Subtitles
                    for (sub in subtitles) {
                        val subUrl = sub.file ?: continue
                        subtitleCallback.invoke(
                            SubtitleFile(sub.label ?: sub.language ?: "English", subUrl)
                        )
                    }

                    // If we found streams from this provider, stop trying more URLs for it
                    if (foundAnySources) break
                } catch (e: Exception) {
                    Log.d("Anivexa", "Provider $provName at $watchPath failed: ${e.message}")
                }
            }
        }

        if (!foundAnySources) {
            Log.e("Anivexa", "No sources found for anilistId=$anilistId, $audioType, ep=$epNum")
        }
        return foundAnySources
    }

    private fun getQualityFromPriority(priority: Int?): Int {
        return when (priority) {
            5 -> 1080  // High
            4 -> 720   // Medium-High
            3 -> 480   // Medium
            2 -> 360   // Low
            else -> 720
        }
    }
}
