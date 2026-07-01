package com.enma

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

class EnmaProvider : MainAPI() {
    override var mainUrl = "https://www.enma.lol"
    override var name = "Enma"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.enma.lol/api"

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "$apiUrl/top-airing" to "Top Airing",
        "$apiUrl/most-favorite" to "Most Favorite",
        "$apiUrl/recently-added" to "Recently Added",
        "$apiUrl/recently-updated" to "Recently Updated",
    )

    // ── Data Classes ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaSearchResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaSearchResults? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaSearchResults(
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("data") val data: List<EnmaAnimeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaAnimeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("data_id") val dataId: String? = null,
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japanese_title") val japaneseTitle: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("tvInfo") val tvInfo: EnmaTvInfo? = null,
        @JsonProperty("adultContent") val adultContent: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaTvInfo(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("sub") val sub: Int? = null,
        @JsonProperty("dub") val dub: Int? = null,
        @JsonProperty("eps") val eps: Int? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("showType") val showType: String? = null,
        @JsonProperty("duration") val duration: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaInfoResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaInfoResults? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaInfoResults(
        @JsonProperty("data") val data: EnmaInfoData? = null,
        @JsonProperty("seasons") val seasons: List<Any>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaInfoData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japanese_title") val japaneseTitle: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("animeInfo") val animeInfo: EnmaAnimeInfo? = null,
        @JsonProperty("adultContent") val adultContent: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaAnimeInfo(
        @JsonProperty("Overview") val overview: String? = null,
        @JsonProperty("tvInfo") val tvInfo: EnmaTvInfo? = null,
        @JsonProperty("Studio") val studio: List<String>? = null,
        @JsonProperty("Genres") val genres: List<String>? = null,
        @JsonProperty("Aired") val aired: String? = null,
        @JsonProperty("Rating") val rating: String? = null,
        @JsonProperty("Status") val status: String? = null,
        @JsonProperty("Episodes") val episodes: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaEpisodesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaEpisodesResults? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaEpisodesResults(
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("airedEpisodes") val airedEpisodes: Int? = null,
        @JsonProperty("episodes") val episodes: List<EnmaEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaEpisode(
        @JsonProperty("episode_no") val episodeNo: Int? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("filler") val filler: Boolean? = null,
        @JsonProperty("recap") val recap: Boolean? = null,
        @JsonProperty("airDate") val airDate: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaServersResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: List<EnmaServer>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaServer(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("data_id") val dataId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaStreamResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaStreamResults? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaStreamResults(
        @JsonProperty("streamingLink") val streamingLink: EnmaStreamingLink? = null,
        @JsonProperty("servers") val servers: List<EnmaServer>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaStreamingLink(
        @JsonProperty("anilistId") val anilistId: String? = null,
        @JsonProperty("episodeNum") val episodeNum: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("iframe") val iframe: String? = null,
        @JsonProperty("server") val server: String? = null
    )

    data class EpisodeLoadData(
        val animeId: String,
        val episodeId: String,
        val episodeNum: Int,
        val type: String
    )

    // ── Main Page ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}?page=$page"
        val response = app.get(url, headers = headers).text
        val parsed = parseJson<EnmaSearchResponse>(response)
        val items = parsed.results?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ── Search ──
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = app.get("$apiUrl/search?keyword=$encoded&page=1", headers = headers).text
        val parsed = parseJson<EnmaSearchResponse>(response)
        return parsed.results?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun EnmaAnimeItem.toSearchResult(): AnimeSearchResponse? {
        val id = id ?: return null
        val title = title ?: return null
        val poster = poster
        val hasSub = tvInfo?.sub != null && tvInfo.sub > 0
        val hasDub = tvInfo?.dub != null && tvInfo.dub > 0
        return newAnimeSearchResponse(title, id, TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = hasDub || !hasSub, subExist = true)
        }
    }

    // ── Load ──
    override suspend fun load(url: String): LoadResponse? {
        val animeId = url // url is the anime id (e.g. "one-piece-21")

        // Fetch anime info
        val infoText = app.get("$apiUrl/info?id=$animeId", headers = headers).text
        val info = parseJson<EnmaInfoResponse>(infoText).results?.data ?: return null
        val title = info.title ?: return null
        val poster = info.poster
        val plot = info.animeInfo?.overview
        val genres = info.animeInfo?.genres ?: emptyList()
        val status = info.animeInfo?.status
        val showStatus = when {
            status?.contains("Currently", ignoreCase = true) == true -> ShowStatus.Ongoing
            status?.contains("Finished", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }
        val tvType = when (info.animeInfo?.tvInfo?.showType) {
            "Movie" -> TvType.AnimeMovie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        // Fetch episodes
        val epsText = app.get("$apiUrl/episodes/$animeId", headers = headers).text
        val epsData = parseJson<EnmaEpisodesResponse>(epsText).results?.episodes ?: emptyList()

        // Check if dub is available by fetching servers for episode 1
        var hasDub = false
        var hasSub = true
        try {
            val firstEpId = epsData.firstOrNull()?.id
            if (firstEpId != null) {
                val encodedId = URLEncoder.encode(firstEpId, "UTF-8")
                val serversText = app.get("$apiUrl/servers/$animeId?ep=1", headers = headers).text
                val servers = parseJson<EnmaServersResponse>(serversText).results ?: emptyList()
                hasDub = servers.any { it.type == "dub" }
                hasSub = servers.any { it.type == "sub" }
            }
        } catch (e: Exception) {
            println("Enma: Failed to check dub availability - ${e.message}")
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        epsData.forEach { ep ->
            val epNum = ep.episodeNo ?: return@forEach
            val epId = ep.id ?: return@forEach
            val epTitle = ep.title?.takeIf { it.isNotBlank() }

            if (hasSub) {
                subEpisodes.add(newEpisode(EpisodeLoadData(animeId, epId, epNum, "sub").toJson()) {
                    this.episode = epNum
                    this.name = epTitle ?: "Episode $epNum"
                    this.description = if (ep.filler == true) "Filler episode" else null
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode(EpisodeLoadData(animeId, epId, epNum, "dub").toJson()) {
                    this.episode = epNum
                    this.name = epTitle ?: "Episode $epNum"
                    this.description = if (ep.filler == true) "Filler episode" else null
                })
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = showStatus
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ── Load Links ──
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = try {
            parseJson<EpisodeLoadData>(data)
        } catch (e: Exception) {
            println("Enma: Failed to parse episode data - ${e.message}")
            return false
        }

        val animeId = loadData.animeId
        val episodeId = loadData.episodeId
        val type = loadData.type // "sub" or "dub"

        // Fetch available servers
        val servers = try {
            val encodedEpId = URLEncoder.encode(episodeId, "UTF-8")
            val serversText = app.get("$apiUrl/servers/$animeId?ep=${loadData.episodeNum}", headers = headers).text
            parseJson<EnmaServersResponse>(serversText).results ?: emptyList()
        } catch (e: Exception) {
            println("Enma: Failed to fetch servers - ${e.message}")
            emptyList()
        }

        // Filter servers by type (sub or dub)
        val typeServers = servers.filter { it.type == type }
        if (typeServers.isEmpty()) {
            println("Enma: No $type servers found for $animeId ep${loadData.episodeNum}")
            return false
        }

        // Generate ENMA server names (ENMA-1, ENMA-2, etc.)
        val enmaServerNames = typeServers.mapIndexed { idx, _ -> "ENMA-${idx + 1}" }

        var found = false
        val seenUrls = mutableSetOf<String>()

        for (serverName in enmaServerNames) {
            try {
                // Fetch stream iframe URL
                val encodedId = URLEncoder.encode(episodeId, "UTF-8")
                val streamUrl = "$apiUrl/stream?id=$encodedId&server=$serverName&type=$type"
                val streamText = app.get(streamUrl, headers = headers).text
                val streamData = parseJson<EnmaStreamResponse>(streamText)
                val iframe = streamData.results?.streamingLink?.iframe ?: continue

                if (!seenUrls.add(iframe)) continue

                // Resolve megaplay.buzz iframe to m3u8
                if (resolveMegaPlay(iframe, serverName, type, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                println("Enma: Failed to resolve $serverName - ${e.message}")
            }
        }

        return found
    }

    /**
     * Resolve a megaplay.buzz iframe URL to m3u8 + subtitles.
     * Chain: fetch page → extract data-id → fetch getSources → get m3u8
     */
    private suspend fun resolveMegaPlay(
        iframeUrl: String,
        serverName: String,
        type: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = try {
                val uri = java.net.URI(iframeUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                "https://megaplay.buzz"
            }

            val pageHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/",
            )

            // 1. Fetch the megaplay page to get data-id
            val doc = app.get(iframeUrl, headers = pageHeaders).document
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-id")
                ?: playerEl?.attr("data-realid")
                ?: return false

            if (streamId.isBlank()) return false

            // 2. Fetch getSources to get m3u8 + subtitles
            val ajaxHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to host,
                "Referer" to iframeUrl,
            )

            val sourcesUrl = "$host/stream/getSources?id=$streamId&type=$type"
            val sourcesText = app.get(sourcesUrl, headers = ajaxHeaders, referer = iframeUrl).text
            val root = JsonParser.parseString(sourcesText).asJsonObject
            val m3u8 = root.getAsJsonObject("sources")?.get("file")?.asString
            if (m3u8.isNullOrBlank()) return false

            // 3. Generate m3u8 links
            val displayType = if (type == "dub") "DUB" else "SUB"
            val m3u8Headers = mapOf(
                "Referer" to "$host/",
                "Origin" to host,
                "User-Agent" to USER_AGENT,
            )

            val generated = M3u8Helper.generateM3u8(
                "Enma $serverName $displayType",
                m3u8,
                host,
                headers = m3u8Headers
            )
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "Enma",
                        name = "Enma $serverName $displayType",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$host/"
                        this.headers = m3u8Headers
                    }
                )
            }

            // 4. Fetch subtitles
            try {
                val tracks = root.getAsJsonArray("tracks")
                if (tracks != null) {
                    for (element in tracks) {
                        val track = element.asJsonObject
                        val kind = track.get("kind")?.asString ?: continue
                        if (kind != "captions" && kind != "subtitles") continue
                        val file = track.get("file")?.asString ?: continue
                        val label = track.get("label")?.asString ?: "English"
                        subtitleCallback.invoke(SubtitleFile(label, file))
                    }
                }
            } catch (_: Exception) {}

            return true
        } catch (e: Exception) {
            println("Enma: MegaPlay resolution failed for $iframeUrl - ${e.message}")
            return false
        }
    }
}
