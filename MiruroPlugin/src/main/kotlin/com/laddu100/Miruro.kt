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
    // kiwi gives best HLS (3 quality levels from animepahe CDN), then other working providers
    private val providerOrder = listOf("kiwi", "pewe", "ally", "bonk", "bee", "hop", "moo")
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
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        // Fetch anime metadata from AniList
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
            // Find provider with sub episodes
            for (provName in providerOrder) {
                val prov = providers[provName] ?: continue
                val subs = prov.episodes?.sub
                if (!subs.isNullOrEmpty()) {
                    subs.forEach { ep ->
                        val epNum = ep.number ?: return@forEach
                        val epId = ep.id ?: return@forEach
                        subEpisodes.add(newEpisode("$anilistId|$epNum|sub") {
                            this.name = ep.title ?: "Episode $epNum"
                            this.episode = epNum
                            this.description = ep.description
                            this.posterUrl = ep.image
                        })
                    }
                    break
                }
            }
            // Find provider with dub episodes
            for (provName in providerOrder) {
                val prov = providers[provName] ?: continue
                val dubs = prov.episodes?.dub
                if (!dubs.isNullOrEmpty()) {
                    dubs.forEach { ep ->
                        val epNum = ep.number ?: return@forEach
                        dubEpisodes.add(newEpisode("$anilistId|$epNum|dub") {
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
            val totalEps = media.episodes ?: 0
            for (i in 1..totalEps) {
                subEpisodes.add(newEpisode("$anilistId|$i|sub") {
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
        val parts = data.split("|")
        if (parts.size < 3) return false
        val anilistId = parts[0].toIntOrNull() ?: return false
        val episodeNumber = parts[1].toIntOrNull() ?: return false
        val audioType = parts[2]
        // Fetch episodes to get episode IDs
        val episodesJson = try {
            miruroPipeRequest(mainUrl, "episodes", mapOf("anilistId" to anilistId))
        } catch (e: Exception) {
            return false
        }
        val episodesData = try {
            parseJson<MiruroEpisodesResponse>(episodesJson)
        } catch (e: Exception) {
            return false
        }
        val providers = episodesData.providers ?: return false
        var foundSources = false
        // Try each provider in order until we find working sources
        for (provName in providerOrder) {
            val provData = providers[provName] ?: continue
            // Get episode list - try the requested audio type first, fall back to sub
            val episodeList = when {
                audioType == "dub" && !provData.episodes?.dub.isNullOrEmpty() -> provData.episodes?.dub
                !provData.episodes?.sub.isNullOrEmpty() -> provData.episodes?.sub
                else -> continue
            }
            val episode = episodeList?.firstOrNull { it.number == episodeNumber } ?: continue
            val episodeId = episode.id ?: continue
            try {
                val sourcesJson = miruroPipeRequest(
                    mainUrl,
                    "sources",
                    mapOf("episodeId" to episodeId, "provider" to provName)
                )
                val sourcesData = parseJson<MiruroSourcesResponse>(sourcesJson)
                val streams = sourcesData.streams ?: continue
                // Get HLS streams (direct M3U8 links - best for playback)
                val hlsStreams = streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }
                if (hlsStreams.isNotEmpty()) {
                    for (stream in hlsStreams) {
                        val m3u8Url = stream.url ?: continue
                        val referer = stream.referer ?: "$mainUrl/"
                        val quality = qualityFromString(stream.quality)
                        val qualityLabel = stream.quality ?: "Auto"
                        val fansubLabel = if (!stream.fansub.isNullOrEmpty()) " [${stream.fansub}]" else ""
                        callback.invoke(
                            newExtractorLink(
                                source = "Miruro",
                                name = "Miruro $provName$fansubLabel - $qualityLabel",
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
                }
                // Also add MP4 streams as fallback
                val mp4Streams = streams.filter { it.type == "mp4" && !it.url.isNullOrEmpty() }
                for (stream in mp4Streams) {
                    val mp4Url = stream.url ?: continue
                    val referer = stream.referer ?: "$mainUrl/"
                    val quality = qualityFromString(stream.quality)
                    val qualityLabel = stream.quality ?: "MP4"
                    callback.invoke(
                        newExtractorLink(
                            source = "Miruro",
                            name = "Miruro $provName (MP4) - $qualityLabel",
                            url = mp4Url,
                            type = ExtractorLinkType.VIDEO
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
                // Add subtitles
                sourcesData.subtitles?.forEach { sub ->
                    if (!sub.url.isNullOrEmpty()) {
                        subtitleCallback.invoke(
                            SubtitleFile(sub.lang ?: "English", sub.url)
                        )
                    }
                }
                if (foundSources) break
            } catch (e: Exception) {
                continue
            }
        }
        return foundSources
    }
    // ─── Helper ─────────────────────────────────────────────────────────────
    private fun toSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }
}
