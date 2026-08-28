package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class RaghavEnma : MainAPI() {
    override var mainUrl = "https://www.enma.lol"
    override var name = "Enma"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.enma.lol/api"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
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

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private suspend fun fetchApi(url: String): String? {
        return EnmaDecryptor.fetchAndDecrypt(url, headers)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("RaghavAnime", "[Enma] getMainPage: ${request.name} page=$page")
        val url = "${request.data}?page=$page"
        val response = try {
            fetchApi(url)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] getMainPage fetch failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        } ?: return newHomePageResponse(request.name, emptyList())

        val parsed = try {
            parseJson<EnmaSearchResponse>(response)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] getMainPage parse failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
        val items = parsed.results?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
        Log.d("RaghavAnime", "[Enma] getMainPage ${request.name}: ${items.size} items")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RaghavAnime", "[Enma] search: q='${query.take(40)}'")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = try {
            fetchApi("$apiUrl/search?keyword=$encoded&page=1")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] search fetch failed: ${e.message}")
            return emptyList()
        } ?: return emptyList()

        val parsed = try {
            parseJson<EnmaSearchResponse>(response)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] search parse failed: ${e.message}")
            return emptyList()
        }
        Log.d("RaghavAnime", "[Enma] search: ${parsed.results?.data?.size ?: 0} results")
        return parsed.results?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun EnmaAnimeItem.toSearchResult(): AnimeSearchResponse? {
        val id = id ?: return null
        val title = title ?: return null
        return newAnimeSearchResponse(title, id, TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = true, subExist = true)
        }
    }

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        title: String,
        jpTitle: String?,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("RaghavAnime", "[Enma] loadLinksByAnilistId: anilist=$anilistId ep=$episode dub=$isDub title='${title.take(40)}'")
        val searchQueries = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        if (searchQueries.isEmpty()) return false

        var matchedId: String? = null
        for (query in searchQueries) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val response = try {
                fetchApi("$apiUrl/search?keyword=$encoded&page=1")
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] anilist search fetch failed: ${e.message}")
                continue
            } ?: continue
            val parsed = try { parseJson<EnmaSearchResponse>(response) } catch (e: Exception) { continue }
            val items = parsed.results?.data ?: emptyList()
            val match = items.firstOrNull { it.anilistId == anilistId }
            if (match != null && match.id != null) {
                Log.d("RaghavAnime", "[Enma] anilist=$anilistId matched id=${match.id}")
                matchedId = match.id
                break
            }
        }
        Log.d("RaghavAnime", "[Enma] anilist search done: matchedId=$matchedId")
        if (matchedId == null) return false

        val loadResult = load("$mainUrl/$matchedId") as? AnimeLoadResponse ?: return false
        val epKey = if (isDub) DubStatus.Dubbed else DubStatus.Subbed
        Log.d("RaghavAnime", "[Enma] finding episode=$episode (epKey=$epKey)")
        val matchedEp = loadResult.episodes?.get(epKey)?.find { it.episode == episode } ?: return false
        return loadLinks(matchedEp.data, false, subtitleCallback, callback)
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: url
        Log.d("RaghavAnime", "[Enma] load: animeId=$animeId")

        val infoText = try {
            fetchApi("$apiUrl/info?id=$animeId")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] info fetch failed: ${e.message}")
            return null
        } ?: return null

        val info = try {
            parseJson<EnmaInfoResponse>(infoText).results?.data
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] info parse failed: ${e.message}")
            return null
        } ?: return null

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

        val epsText = try {
            fetchApi("$apiUrl/episodes/$animeId")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] episodes fetch failed: ${e.message}")
            return null
        } ?: return null

        val epsData = try {
            parseJson<EnmaEpisodesResponse>(epsText).results?.episodes
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] episodes parse failed: ${e.message}")
            return null
        } ?: emptyList()

        var hasDub = false
        var hasSub = true
        try {
            val serversText = fetchApi("$apiUrl/servers/$animeId?ep=1")
            if (!serversText.isNullOrBlank()) {
                val servers = parseJson<EnmaServersResponse>(serversText).results ?: emptyList()
                hasDub = servers.any { it.type == "dub" }
                hasSub = servers.any { it.type == "sub" }
                Log.d("RaghavAnime", "[Enma] dub/sub probe: ${servers.size} servers -> hasSub=$hasSub hasDub=$hasDub")
            }
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] servers probe failed: ${e.message}")
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

        Log.d("RaghavAnime", "[Enma] load ok: ${epsData.size} eps -> ${subEpisodes.size} sub, ${dubEpisodes.size} dub")
        val finalType = if (tvType == TvType.AnimeMovie && dubEpisodes.isNotEmpty()) TvType.Anime else tvType
        return newAnimeLoadResponse(title, url, finalType) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = showStatus
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
        val loadData = try {
            parseJson<EpisodeLoadData>(data)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] loadLinks: bad data '${data.take(60)}'")
            return false
        }

        val animeId = loadData.animeId
        val episodeId = loadData.episodeId
        val type = loadData.type
        val epNum = loadData.episodeNum
        Log.d("RaghavAnime", "[Enma] loadLinks: animeId=$animeId ep=$epNum type=$type epId=$episodeId")

        val servers = try {
            val serversText = fetchApi("$apiUrl/servers/$animeId?ep=$epNum")
            if (serversText.isNullOrBlank()) emptyList()
            else parseJson<EnmaServersResponse>(serversText).results ?: emptyList()
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] servers fetch failed for ep=$epNum: ${e.message}")
            emptyList()
        }

        Log.d("RaghavAnime", "[Enma] servers: ${servers.size} total")
        val typeServers = servers.filter { it.type == type }
        Log.d("RaghavAnime", "[Enma] type=$type servers: ${typeServers.size}")
        if (typeServers.isEmpty()) return false

        val serverNames = typeServers.mapNotNull { it.serverName?.takeIf { n -> n.isNotBlank() } }
            .ifEmpty { typeServers.mapIndexed { idx, _ -> "ENMA-${idx + 1}" } }

        var found = false
        val seenUrls = mutableSetOf<String>()
        Log.d("RaghavAnime", "[Enma] trying servers: ${serverNames.joinToString(",").take(120)}")

        for (serverName in serverNames) {
            try {
                Log.d("RaghavAnime", "[Enma] server=$serverName: fetching stream")
                val encodedId = URLEncoder.encode(episodeId, "UTF-8")
                val streamUrl = "$apiUrl/stream?id=$encodedId&server=$serverName&type=$type"
                val streamText = fetchApi(streamUrl) ?: continue
                val streamData = parseJson<EnmaStreamResponse>(streamText)
                val iframe = streamData.results?.streamingLink?.iframe ?: continue
                Log.d("RaghavAnime", "[Enma] server=$serverName iframe=${iframe.take(120)}")

                if (!seenUrls.add(iframe)) continue

                val domain = Regex("""https?://([^/]+)""").find(iframe)?.groupValues?.get(1) ?: ""
                Log.d("RaghavAnime", "[Enma] iframe host=$domain")
                val displayType = if (type == "dub") "DUB" else "SUB"

                val resolved = when {
                    domain.contains("megaplay", ignoreCase = true) ->
                        resolveMegaPlay(iframe, serverName, type, subtitleCallback, callback)
                    domain.contains("4animo", ignoreCase = true) ->
                        resolve4Animo(iframe, serverName, displayType, subtitleCallback, callback)
                    domain.contains("vidnest", ignoreCase = true) ->
                        resolveVidnest(iframe, serverName, displayType, subtitleCallback, callback)
                    domain.contains("tryembed", ignoreCase = true) ->
                        resolveTryEmbed(iframe, serverName, displayType, subtitleCallback, callback)
                    else -> {
                        try {
                            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                if (resolved) found = true
            } catch (e: Exception) {
                Log.d("Enma", "Failed to resolve $serverName: ${e.message}")
            }
        }

        Log.d("RaghavAnime", "[Enma] loadLinks done: found=$found")
        return found
    }

    private suspend fun resolve4Animo(
        iframeUrl: String,
        serverName: String,
        displayType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return false

            val pageHeaders = mapOf(
                "User-Agent" to mobileUA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            )

            Log.d("RaghavAnime", "[Enma] 4Animo resolving: ${iframeUrl.take(120)}")
            val embedHtml = app.get(iframeUrl, headers = pageHeaders).text

            val sourcesPath = Regex("""var\s+sourcesUrl\s*=\s*['"]([^'"]+)['"]""")
                .find(embedHtml)?.groupValues?.get(1) ?: return false

            val sourcesApiUrl = if (sourcesPath.startsWith("http")) sourcesPath else "$host$sourcesPath"
            Log.d("RaghavAnime", "[Enma] 4Animo sources url=${sourcesApiUrl.take(120)}")
            val ajaxHeaders = mapOf(
                "User-Agent" to mobileUA,
                "Accept" to "*/*",
                "Referer" to iframeUrl
            )
            val sourcesText = app.get(sourcesApiUrl, headers = ajaxHeaders, referer = iframeUrl).text

            val root = try {
                JsonParser.parseString(sourcesText).asJsonObject
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] 4Animo sources parse failed: ${e.message}")
                return false
            }

            val m3u8Path = try {
                val sourcesEl = root.get("sources")
                if (sourcesEl?.isJsonArray == true && sourcesEl.asJsonArray.size() > 0) {
                    sourcesEl.asJsonArray[0].asJsonObject.get("file")?.asString
                } else if (sourcesEl?.isJsonObject == true) {
                    sourcesEl.asJsonObject.get("file")?.asString
                } else null
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] 4Animo m3u8 extract failed: ${e.message}")
                null
            } ?: return false

            val fullM3u8 = if (m3u8Path.startsWith("http")) m3u8Path else "$host$m3u8Path"
            Log.d("RaghavAnime", "[Enma] 4Animo link: ${fullM3u8.take(120)}")

            callback.invoke(
                newExtractorLink(
                    source = "Enma",
                    name = "Enma $serverName $displayType",
                    url = fullM3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.headers = mapOf("Referer" to iframeUrl, "User-Agent" to mobileUA)
                }
            )

            try {
                root.getAsJsonArray("tracks")?.forEach { element ->
                    val track = element.asJsonObject
                    val kind = track.get("kind")?.asString ?: return@forEach
                    if (kind != "captions" && kind != "subtitles") return@forEach
                    val file = track.get("file")?.asString ?: return@forEach
                    val label = track.get("label")?.asString ?: "English"
                    val fullSub = if (file.startsWith("http")) file else "$host$file"
                    subtitleCallback.invoke(newSubtitleFile(label, fullSub))
                }
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] 4Animo tracks failed: ${e.message}")
            }

            return true
        } catch (e: Exception) {
            Log.d("Enma", "4Animo failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveVidnest(
        iframeUrl: String,
        serverName: String,
        displayType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return false
            Log.d("RaghavAnime", "[Enma] Vidnest resolving: ${iframeUrl.take(120)}")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.mp4""")),
                script = """try{var b=document.querySelector('button,[class*=play],.vjs-big-play-button');if(b){b.click()}}catch(e){}""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(iframeUrl, referer = "$mainUrl/", interceptor = resolver).url
            Log.d("RaghavAnime", "[Enma] Vidnest resolved: ${resolved.take(120)}")
            if (resolved.contains(".m3u8", true)) {
                val proxyHost = Regex("""(https?://[^/]+)""").find(resolved)?.groupValues?.get(1) ?: host
                Log.d("RaghavAnime", "[Enma] Vidnest: m3u8 ok, generating links for $serverName")
                M3u8Helper.generateM3u8(
                    "Enma $serverName $displayType", resolved, proxyHost
                ).forEach(callback)
                return true
            }
            Log.w("RaghavAnime", "[Enma] Vidnest: resolved url not m3u8 for $serverName")
            return false
        } catch (e: Exception) {
            Log.d("Enma", "Vidnest failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveTryEmbed(
        iframeUrl: String,
        serverName: String,
        displayType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return false
            Log.d("RaghavAnime", "[Enma] TryEmbed resolving: ${iframeUrl.take(120)}")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.mp4""")),
                script = """try{var b=document.querySelector('button,.vjs-big-play-button,[class*=play]');if(b){b.click()}}catch(e){}""",
                useOkhttp = false,
                timeout = 25_000L
            )
            val resolved = app.get(iframeUrl, referer = "$mainUrl/", interceptor = resolver).url
            Log.d("RaghavAnime", "[Enma] TryEmbed resolved: ${resolved.take(120)}")
            if (resolved.contains(".m3u8", true)) {
                val finalUrl = try {
                    app.get(resolved, referer = "$host/", headers = mapOf("User-Agent" to mobileUA)).url
                } catch (e: Exception) {
                    Log.e("RaghavAnime", "[Enma] TryEmbed follow redirect failed: ${e.message}")
                    resolved
                }
                val finalHost = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: host
                Log.d("RaghavAnime", "[Enma] TryEmbed link: ${finalUrl.take(120)}")
                callback.invoke(
                    newExtractorLink(
                        source = "Enma",
                        name = "Enma $serverName $displayType",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$finalHost/"
                        this.headers = mapOf("Referer" to "$finalHost/", "User-Agent" to mobileUA)
                    }
                )
                return true
            }
            Log.w("RaghavAnime", "[Enma] TryEmbed: resolved url not m3u8 for $serverName")
            return false
        } catch (e: Exception) {
            Log.d("Enma", "TryEmbed failed: ${e.message}")
            return false
        }
    }

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
                Log.e("RaghavAnime", "[Enma] MegaPlay host parse failed: ${e.message}")
                "https://megaplay.buzz"
            }

            val pageHeaders = mapOf(
                "User-Agent" to mobileUA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/",
            )

            Log.d("RaghavAnime", "[Enma] MegaPlay resolving: ${iframeUrl.take(120)}")
            val doc = app.get(iframeUrl, headers = pageHeaders).document
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-id")
                ?: playerEl?.attr("data-realid")
                ?: return false
            if (streamId.isBlank()) return false
            Log.d("RaghavAnime", "[Enma] MegaPlay streamId=$streamId")

            val ajaxHeaders = mapOf(
                "User-Agent" to mobileUA,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to host,
                "Referer" to iframeUrl,
            )

            val sourcesUrl = "$host/stream/getSources?id=$streamId&type=$type"
            val sourcesText = app.get(sourcesUrl, headers = ajaxHeaders, referer = iframeUrl).text
            val root = JsonParser.parseString(sourcesText).asJsonObject

            val m3u8 = try {
                val sourcesEl = root.get("sources")
                if (sourcesEl?.isJsonObject == true) {
                    sourcesEl.asJsonObject.get("file")?.asString
                } else if (sourcesEl?.isJsonArray == true && sourcesEl.asJsonArray.size() > 0) {
                    sourcesEl.asJsonArray[0].asJsonObject.get("file")?.asString
                } else null
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] MegaPlay m3u8 extract failed: ${e.message}")
                null
            }

            if (m3u8.isNullOrBlank()) return false
            Log.d("RaghavAnime", "[Enma] MegaPlay m3u8=${m3u8.take(120)}")

            val displayType = if (type == "dub") "DUB" else "SUB"
            val m3u8Headers = mapOf(
                "Referer" to "$host/",
                "Origin" to host,
                "User-Agent" to mobileUA,
            )

            val generated = M3u8Helper.generateM3u8(
                "Enma $serverName $displayType", m3u8, host, headers = m3u8Headers
            )
            Log.d("RaghavAnime", "[Enma] MegaPlay generated ${generated.size} links for $serverName")
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                Log.w("RaghavAnime", "[Enma] MegaPlay: generateM3u8 empty, emitting raw m3u8")
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

            try {
                root.getAsJsonArray("tracks")?.forEach { element ->
                    val track = element.asJsonObject
                    val kind = track.get("kind")?.asString ?: return@forEach
                    if (kind != "captions" && kind != "subtitles") return@forEach
                    val file = track.get("file")?.asString ?: return@forEach
                    val label = track.get("label")?.asString ?: "English"
                    subtitleCallback.invoke(newSubtitleFile(label, file))
                }
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] MegaPlay tracks failed: ${e.message}")
            }

            return true
        } catch (e: Exception) {
            Log.d("Enma", "MegaPlay failed: ${e.message}")
            return false
        }
    }
}
