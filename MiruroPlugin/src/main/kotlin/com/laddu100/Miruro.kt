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

    private val providerOrder = listOf("kiwi", "pewe", "bonk", "bee", "ally", "hop", "moo")

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
            newAnimeSearchResponse(title, "$mainUrl/info/$id/${toSlug(title)}", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
        val responseText = anilistQuery(SEARCH_QUERY, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id/${toSlug(title)}", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
        val infoResponse = parseJson<AniListResponse>(infoText)
        val media = infoResponse.data?.Media ?: return null

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

        try {
            val episodesJson = miruroPipeRequest(mainUrl, "episodes", mapOf("anilistId" to anilistId))
            val episodesData = parseJson<MiruroEpisodesResponse>(episodesJson)
            val providers = episodesData.providers ?: emptyMap()

            // ── Sub episodes ──
            var bestSubProvider: String? = null
            var bestSubCount = 0
            for (provName in providerOrder) {
                val count = providers[provName]?.episodes?.sub?.size ?: 0
                if (count > bestSubCount) { bestSubCount = count; bestSubProvider = provName }
            }
            if (bestSubProvider != null) {
                val subList = providers[bestSubProvider]!!.episodes!!.sub!!
                subList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    // Format: sub|anilistId|prov1:id1|prov2:id2|prov3:id3
                    val parts = mutableListOf("sub", anilistId.toString())
                    for (provName in providerOrder) {
                        val provEps = providers[provName]?.episodes?.sub ?: continue
                        val match = provEps.firstOrNull { it.number == epNum }
                        if (match?.id != null) parts.add("$provName:${match.id}")
                    }
                    subEpisodes.add(newEpisode(parts.joinToString("|")) {
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
                val count = providers[provName]?.episodes?.dub?.size ?: 0
                if (count > bestDubCount) { bestDubCount = count; bestDubProvider = provName }
            }
            if (bestDubProvider != null) {
                val dubList = providers[bestDubProvider]!!.episodes!!.dub!!
                dubList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    // Format: dub|anilistId|prov1:id1|prov2:id2|prov3:id3
                    val parts = mutableListOf("dub", anilistId.toString())
                    for (provName in providerOrder) {
                        val provEps = providers[provName]?.episodes?.dub ?: continue
                        val match = provEps.firstOrNull { it.number == epNum }
                        if (match?.id != null) parts.add("$provName:${match.id}")
                    }
                    dubEpisodes.add(newEpisode(parts.joinToString("|")) {
                        this.name = ep.title ?: "Episode $epNum"
                        this.episode = epNum
                        this.description = ep.description
                        this.posterUrl = ep.image
                    })
                }
            }
        } catch (_: Exception) {}

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
        // Data format: sub|anilistId|prov1:id1|prov2:id2  OR  dub|anilistId|prov1:id1|prov2:id2
        val parts = data.split("|")
        if (parts.size < 3) return false

        val category = parts[0]  // "sub" or "dub"
        val anilistId = parts[1].toIntOrNull()
        val providerEntries = parts.drop(2)  // ["prov1:id1", "prov2:id2", ...]

        val suffix = if (category == "dub") "Dub" else "Sub"
        val wrappedCallback = { link: ExtractorLink ->
            val newName = if (link.name.contains(suffix)) link.name else "${link.name} ($suffix)"
            callback.invoke(
                newExtractorLink(
                    source = link.source,
                    name = newName,
                    url = link.url,
                    referer = link.referer,
                    type = link.type
                ) {
                    this.quality = link.quality
                    this.headers = link.headers
                }
            )
        }

        for (entry in providerEntries) {
            val colonIdx = entry.indexOf(':')
            if (colonIdx < 0) continue
            val provider = entry.substring(0, colonIdx)
            val episodeId = entry.substring(colonIdx + 1)
            if (provider.isEmpty() || episodeId.isEmpty()) continue

            try {
                // Determine query category param
                val queryCategory = if (category == "dub") "dub" else {
                    if (provider in listOf("bee", "hop", "bonk")) "ssub" else "sub"
                }

                val queryMap = mutableMapOf<String, Any>(
                    "episodeId" to episodeId,
                    "provider" to provider,
                    "category" to queryCategory
                )
                if (anilistId != null) {
                    queryMap["anilistId"] = anilistId
                }

                val sourcesJson = miruroPipeRequest(
                    mainUrl,
                    "sources",
                    queryMap
                )
                val sourcesData = parseJson<MiruroSourcesResponse>(sourcesJson)
                val streams = sourcesData.streams ?: continue
                var foundSources = false

                // HLS streams
                for (stream in streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }) {
                    val m3u8Url = stream.url ?: continue
                    val referer = stream.referer ?: "$mainUrl/"
                    val quality = qualityFromString(stream.quality)
                    val qualityLabel = stream.quality ?: "Auto"
                    val fansubLabel = if (!stream.fansub.isNullOrEmpty()) " [${stream.fansub}]" else ""

                    wrappedCallback.invoke(
                        newExtractorLink(
                            source = "Miruro",
                            name = "Miruro $provider$fansubLabel - $qualityLabel",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            )
                        }
                    )
                    foundSources = true
                }

                // MP4 fallback
                for (stream in streams.filter { it.type == "mp4" && !it.url.isNullOrEmpty() }) {
                    val mp4Url = stream.url ?: continue
                    val referer = stream.referer ?: "$mainUrl/"
                    wrappedCallback.invoke(
                        newExtractorLink(
                            source = "Miruro",
                            name = "Miruro $provider (MP4)",
                            url = mp4Url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityFromString(stream.quality)
                            this.headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            )
                        }
                    )
                    foundSources = true
                }

                // Embed streams
                for (stream in streams.filter { it.type == "embed" && !it.url.isNullOrEmpty() }) {
                    val embedUrl = stream.url ?: continue
                    val referer = stream.referer ?: "$mainUrl/"
                    try {
                        loadExtractor(embedUrl, referer, subtitleCallback, wrappedCallback)
                        foundSources = true
                    } catch (_: Exception) {}
                }

                // Subtitles
                sourcesData.subtitles?.forEach { sub ->
                    if (!sub.url.isNullOrEmpty()) {
                        subtitleCallback.invoke(SubtitleFile(sub.lang ?: "English", sub.url))
                    }
                }

                if (foundSources) return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    private fun toSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }
}
