package com.laddu100

import android.content.Context
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
import java.util.concurrent.ConcurrentHashMap

class Miruro : MainAPI() {

    companion object {
        // Set by MiruroPlugin.load(context) — needed for WebView-based Cloudflare bypass
        var context: Context? = null

        // Episode list cache: anilistId -> (subEps, dubEps, timestamp)
        // Avoids re-triggering Cloudflare warm-up when switching between episodes
        private data class CachedEps(
            val sub: List<Episode>,
            val dub: List<Episode>,
            val timestamp: Long
        )
        private val epsCache = ConcurrentHashMap<Int, CachedEps>()
        private val EPS_CACHE_TTL = 300_000L // 5 minutes
    }

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

    // Full provider list — tried in order for episodes & sources.
    // Expanded to include all known Miruro providers for maximum source coverage.
    private val providerOrder = listOf(
        "kiwi", "pewe", "bonk", "bee", "ally", "hop",
        "moo", "nun", "bun", "twin", "cog",
        "mega", "nova", "wave", "zen", "flux"
    )

    // Display names for providers
    private val providerDisplayNames = mapOf(
        "kiwi" to "AnimePahe",
        "pewe" to "AniDB",
        "bonk" to "AnimeDao",
        "bee"  to "AniKoto",
        "ally" to "AllManga",
        "hop"  to "KickAssAnime",
        "moo"  to "AnimeGG",
        "nun"  to "Nun",
        "bun"  to "Bun",
        "twin" to "Twin",
        "cog"  to "Cog",
        "mega" to "MegaAnime",
        "nova" to "Nova",
        "wave" to "Wave",
        "zen"  to "Zen",
        "flux" to "Flux"
    )

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
            "MOVIE" -> TvType.Anime
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        // ── Check episode cache first ──
        val cached = epsCache[anilistId]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < EPS_CACHE_TTL) {
            return newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                if (animeScore != null) this.score = Score.from10((animeScore / 10).toString())
                this.showStatus = showStatus
                addAniListId(anilistId)
                if (cached.sub.isNotEmpty()) addEpisodes(DubStatus.Subbed, cached.sub)
                if (cached.dub.isNotEmpty()) addEpisodes(DubStatus.Dubbed, cached.dub)
            }
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        var loadError: String? = null

        try {
            val episodesJson = miruroPipeRequest("episodes", mapOf("anilistId" to anilistId))
            val episodesData = parseJson<MiruroEpisodesResponse>(episodesJson)
            val providers = episodesData.providers ?: emptyMap()

            // Log available providers for debugging
            println("Miruro: Providers for $anilistId: ${providers.keys}")

            // ── Sub episodes ──
            // Find provider with most sub/ssub episodes for the episode list
            var bestSubProvider: String? = null
            var bestSubCount = 0
            for (provName in providerOrder) {
                val prov = providers[provName] ?: continue
                val subCount = prov.episodes?.sub?.size ?: 0
                val ssubCount = prov.episodes?.ssub?.size ?: 0
                val count = maxOf(subCount, ssubCount)
                if (count > bestSubCount) { bestSubCount = count; bestSubProvider = provName }
            }
            // Also check providers not in our predefined order
            if (bestSubProvider == null || bestSubCount == 0) {
                for ((provName, prov) in providers) {
                    val subCount = prov.episodes?.sub?.size ?: 0
                    val ssubCount = prov.episodes?.ssub?.size ?: 0
                    val count = maxOf(subCount, ssubCount)
                    if (count > bestSubCount) { bestSubCount = count; bestSubProvider = provName }
                }
            }
            if (bestSubProvider != null) {
                val bestProv = providers[bestSubProvider]!!
                val epList = bestProv.episodes!!.let { it.sub ?: it.ssub } ?: emptyList()
                epList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    // Format: sub|anilistId|prov1:id1:cat|prov2:id2:cat
                    val parts = mutableListOf("sub", anilistId.toString())
                    for (provName in providerOrder) {
                        val provEps = providers[provName]?.episodes ?: continue
                        val subMatch = provEps.sub?.firstOrNull { it.number == epNum }
                        val ssubMatch = provEps.ssub?.firstOrNull { it.number == epNum }
                        if (subMatch?.id != null) {
                            parts.add("$provName:${subMatch.id}:sub")
                        } else if (ssubMatch?.id != null) {
                            parts.add("$provName:${ssubMatch.id}:ssub")
                        }
                    }
                    // Also check providers not in predefined order
                    for ((provName, prov) in providers) {
                        if (provName in providerOrder) continue
                        val provEps = prov.episodes ?: continue
                        val subMatch = provEps.sub?.firstOrNull { it.number == epNum }
                        val ssubMatch = provEps.ssub?.firstOrNull { it.number == epNum }
                        if (subMatch?.id != null) {
                            parts.add("$provName:${subMatch.id}:sub")
                        } else if (ssubMatch?.id != null) {
                            parts.add("$provName:${ssubMatch.id}:ssub")
                        }
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
            if (bestDubProvider == null || bestDubCount == 0) {
                for ((provName, prov) in providers) {
                    val count = prov.episodes?.dub?.size ?: 0
                    if (count > bestDubCount) { bestDubCount = count; bestDubProvider = provName }
                }
            }
            if (bestDubProvider != null) {
                val dubList = providers[bestDubProvider]!!.episodes!!.dub!!
                dubList.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    val parts = mutableListOf("dub", anilistId.toString())
                    for (provName in providerOrder) {
                        val provEps = providers[provName]?.episodes ?: continue
                        val dubMatch = provEps.dub?.firstOrNull { it.number == epNum }
                        if (dubMatch?.id != null) {
                            parts.add("$provName:${dubMatch.id}:dub")
                        }
                    }
                    for ((provName, prov) in providers) {
                        if (provName in providerOrder) continue
                        val provEps = prov.episodes ?: continue
                        val dubMatch = provEps.dub?.firstOrNull { it.number == epNum }
                        if (dubMatch?.id != null) {
                            parts.add("$provName:${dubMatch.id}:dub")
                        }
                    }
                    if (parts.size > 2) {
                        dubEpisodes.add(newEpisode(parts.joinToString("|")) {
                            this.name = ep.title ?: "Episode $epNum"
                            this.episode = epNum
                            this.description = ep.description
                            this.posterUrl = ep.image
                        })
                    }
                }
            }

            // Cache the result
            epsCache[anilistId] = CachedEps(
                sub = subEpisodes.toList(),
                dub = dubEpisodes.toList(),
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            println("Miruro: Failed to load episodes for $anilistId - ${e.message}")
            loadError = e.message
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
            // If no episodes loaded, add a notice episode so the user sees
            // WHY (Cloudflare/network) instead of a bare "coming soon".
            if (subEpisodes.isEmpty() && dubEpisodes.isEmpty() && loadError != null) {
                val errMsg = "Failed to load episodes: ${loadError.take(80)}"
                subEpisodes.add(newEpisode("error|$anilistId") {
                    this.name = errMsg
                    this.episode = 0
                })
                addEpisodes(DubStatus.Subbed, subEpisodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false

        // Handle error placeholder
        if (parts[0] == "error") return false

        if (parts.size < 3) return false

        val dubOrSub = parts[0]  // "sub" or "dub"
        val anilistId = parts[1].toIntOrNull()
        val providerEntries = parts.drop(2)  // ["prov1:id1:cat", "prov2:id2:cat", ...]

        var foundAnySources = false
        val seenUrls = mutableSetOf<String>()

        for (entry in providerEntries) {
            val colonParts = entry.split(":")
            if (colonParts.size < 3) {
                // Backward compat: old format "provider:episodeId" without category
                if (colonParts.size == 2) {
                    val provider = colonParts[0]
                    val episodeId = colonParts[1]
                    val category = dubOrSub
                    processProvider(provider, episodeId, category, anilistId, seenUrls, subtitleCallback, callback)?.let {
                        foundAnySources = true
                    }
                }
                continue
            }
            val provider = colonParts[0]
            val category = colonParts.last()
            val episodeId = colonParts.drop(1).dropLast(1).joinToString(":")

            if (provider.isEmpty() || episodeId.isEmpty() || category.isEmpty()) continue

            processProvider(provider, episodeId, category, anilistId, seenUrls, subtitleCallback, callback)?.let {
                foundAnySources = true
            }
        }
        return foundAnySources
    }

    private suspend fun processProvider(
        provider: String,
        episodeId: String,
        category: String,
        anilistId: Int?,
        seenUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean? {
        val displayName = providerDisplayNames[provider] ?: provider

        try {
            val queryMap = mutableMapOf<String, Any>(
                "episodeId" to episodeId,
                "provider" to provider,
                "category" to category
            )
            if (anilistId != null) {
                queryMap["anilistId"] = anilistId
            }

            val sourcesJson = miruroPipeRequest("sources", queryMap)
            val sourcesData = parseJson<MiruroSourcesResponse>(sourcesJson)
            val streams = sourcesData.streams ?: return null

            var found = false

            // HLS streams
            for (stream in streams.filter { it.type == "hls" && !it.url.isNullOrEmpty() }) {
                val m3u8Url = stream.url ?: continue
                if (!seenUrls.add(m3u8Url)) continue

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                val quality = qualityFromString(stream.quality)
                val qualityLabel = stream.quality ?: "Auto"
                val fansubLabel = if (!stream.fansub.isNullOrEmpty()) " [${stream.fansub}]" else ""
                val userAgent = CF_USER_AGENT

                callback.invoke(
                    newExtractorLink(
                        source = "Miruro",
                        name = "$displayName$fansubLabel - $qualityLabel",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to userAgent
                        )
                    }
                )
                found = true
            }

            // MP4 streams
            for (stream in streams.filter { it.type == "mp4" && !it.url.isNullOrEmpty() }) {
                val mp4Url = stream.url ?: continue
                if (!seenUrls.add(mp4Url)) continue

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                val qualityLabel = stream.quality ?: "SD"

                callback.invoke(
                    newExtractorLink(
                        source = "Miruro",
                        name = "$displayName (MP4) - $qualityLabel",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityFromString(stream.quality)
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to CF_USER_AGENT
                        )
                    }
                )
                found = true
            }

            // Embed streams
            for (stream in streams.filter { it.type == "embed" && !it.url.isNullOrEmpty() }) {
                val embedUrl = stream.url ?: continue
                if (!seenUrls.add(embedUrl)) continue

                val referer = stream.referer ?: "${MiruroCloudflare.getWorkingDomain()}/"
                try {
                    if (embedUrl.contains("megaplay.buzz") || embedUrl.contains("megaplay")) {
                        MiruroMegaPlay().getUrl(embedUrl, referer, subtitleCallback, callback)
                        found = true
                    } else if (embedUrl.contains("vidwish.live") || embedUrl.contains("vidwish")) {
                        MiruroVidWish().getUrl(embedUrl, referer, subtitleCallback, callback)
                        found = true
                    } else {
                        try {
                            loadExtractor(embedUrl, referer, subtitleCallback, callback)
                            found = true
                        } catch (_: Exception) {
                            val host = try { java.net.URL(embedUrl).host } catch (_: Exception) { "" }
                            if (host.isNotEmpty()) {
                                MiruroWebView(host, "https://$host").getUrl(embedUrl, referer, subtitleCallback, callback)
                                found = true
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Subtitles
            sourcesData.subtitles?.forEach { sub ->
                if (!sub.url.isNullOrEmpty()) {
                    subtitleCallback.invoke(SubtitleFile(sub.lang ?: "English", sub.url))
                }
            }

            return if (found) true else null
        } catch (e: Exception) {
            println("Miruro: processProvider failed for $provider - ${e.message}")
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
