package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
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
        "User-Agent" to EnmaDecryptor.USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "$apiUrl/top-airing" to "Top Airing",
        "$apiUrl/most-popular" to "Most Popular",
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
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japanese_title") val japaneseTitle: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaInfoResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaInfoResults? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaInfoResults(
        @JsonProperty("data") val data: EnmaInfoData? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaInfoData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("showType") val showType: String? = null,
        @JsonProperty("animeInfo") val animeInfo: EnmaAnimeInfo? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaAnimeInfo(
        @JsonProperty("Overview") val overview: String? = null,
        @JsonProperty("Genres") val genres: List<String>? = null,
        @JsonProperty("Status") val status: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaEpisodesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaEpisodesResults? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaEpisodesResults(
        @JsonProperty("episodes") val episodes: List<EnmaEpisode>? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaEpisode(
        @JsonProperty("episode_no") val episodeNo: Int? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("filler") val filler: Boolean? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaServersResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: List<EnmaServer>? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaServer(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("serverName") val serverName: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaStreamResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("results") val results: EnmaStreamResults? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaStreamResults(
        @JsonProperty("streamingLink") val streamingLink: EnmaStreamingLink? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnmaStreamingLink(
        @JsonProperty("iframe") val iframe: String? = null
    )

    data class EpisodeLoadData(
        val animeId: String,
        val episodeId: String,
        val episodeNum: Int,
        val type: String
    )

    private suspend fun fetchApi(url: String): String? {
        return EnmaDecryptor.fetchAndDecrypt(url, headers)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = try {
            fetchApi("${request.data}?page=$page")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] getMainPage ${request.name} fetch failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        } ?: return newHomePageResponse(request.name, emptyList())

        val items = try {
            parseJson<EnmaSearchResponse>(response).results?.data
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] getMainPage ${request.name} parse failed: ${e.message}")
            null
        }?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = try {
            fetchApi("$apiUrl/search?keyword=$encoded&page=1")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] search fetch failed: ${e.message}")
            return emptyList()
        } ?: return emptyList()

        return try {
            parseJson<EnmaSearchResponse>(response).results?.data
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] search parse failed: ${e.message}")
            emptyList()
        }?.mapNotNull { it.toSearchResult() } ?: emptyList()
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
            val parsed = try { parseJson<EnmaSearchResponse>(response) } catch (e: Exception) {
                Log.e("RaghavAnime", "[Enma] anilist search parse failed: ${e.message}")
                continue
            }
            val match = parsed.results?.data?.firstOrNull { it.anilistId == anilistId && it.id != null }
            if (match != null) {
                matchedId = match.id
                break
            }
        }
        val animeId = matchedId ?: return false

        val loadResult = load("$mainUrl/$animeId") as? AnimeLoadResponse ?: return false
        val epKey = if (isDub) DubStatus.Dubbed else DubStatus.Subbed
        val matchedEp = loadResult.episodes?.get(epKey)?.find { it.episode == episode } ?: return false
        return loadLinks(matchedEp.data, false, subtitleCallback, callback)
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: url

        val infoText = try {
            fetchApi("$apiUrl/info?id=$animeId")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] info fetch failed for $animeId: ${e.message}")
            return null
        } ?: return null

        val info = try {
            parseJson<EnmaInfoResponse>(infoText).results?.data
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] info parse failed for $animeId: ${e.message}")
            null
        } ?: return null

        val title = info.title ?: return null
        val status = info.animeInfo?.status
        val showStatus = when {
            status?.contains("Currently", ignoreCase = true) == true -> ShowStatus.Ongoing
            status?.contains("Finished", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }
        val tvType = when (info.showType) {
            "Movie" -> TvType.AnimeMovie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        val epsText = try {
            fetchApi("$apiUrl/episodes/$animeId")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] episodes fetch failed for $animeId: ${e.message}")
            return null
        } ?: return null

        val epsData = try {
            parseJson<EnmaEpisodesResponse>(epsText).results?.episodes
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] episodes parse failed for $animeId: ${e.message}")
            null
        } ?: emptyList()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        epsData.forEach { ep ->
            val epNum = ep.episodeNo ?: return@forEach
            val epId = ep.id ?: return@forEach
            val epTitle = ep.title?.takeIf { it.isNotBlank() }
            val fillerNote = if (ep.filler == true) "Filler episode" else null

            subEpisodes.add(newEpisode(EpisodeLoadData(animeId, epId, epNum, "sub").toJson()) {
                this.episode = epNum
                this.name = epTitle ?: "Episode $epNum"
                this.description = fillerNote
            })
            dubEpisodes.add(newEpisode(EpisodeLoadData(animeId, epId, epNum, "dub").toJson()) {
                this.episode = epNum
                this.name = epTitle ?: "Episode $epNum"
                this.description = fillerNote
            })
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = info.poster
            this.plot = info.animeInfo?.overview
            this.tags = info.animeInfo?.genres ?: emptyList()
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
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
            Log.e("RaghavAnime", "[Enma] loadLinks got bad data: ${e.message}")
            return false
        }

        val servers = try {
            val serversText = fetchApi("$apiUrl/servers/${loadData.animeId}?ep=${loadData.episodeNum}")
            if (serversText.isNullOrBlank()) emptyList()
            else parseJson<EnmaServersResponse>(serversText).results ?: emptyList()
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Enma] servers fetch failed for ${loadData.animeId} ep${loadData.episodeNum}: ${e.message}")
            emptyList()
        }

        val wantedTypes = if (loadData.type == "dub") listOf("dub") else listOf("sub", "hsub")
        val seenIframes = mutableSetOf<String>()
        var found = false

        for (groupType in wantedTypes) {
            val group = servers.filter { it.type == groupType }
            group.forEach { server ->
                val apiName = server.serverName?.takeIf { it.isNotBlank() } ?: return@forEach
                val label = if (groupType == "hsub") "Enma $apiName hardsub" else "Enma $apiName"
                try {
                    val encodedId = URLEncoder.encode(loadData.episodeId, "UTF-8")
                    val streamText = fetchApi("$apiUrl/stream?id=$encodedId&server=$apiName&type=$groupType")
                        ?: return@forEach
                    val iframe = parseJson<EnmaStreamResponse>(streamText).results?.streamingLink?.iframe
                        ?: return@forEach
                    if (!seenIframes.add(iframe)) return@forEach

                    val host = Regex("""https?://([^/]+)""").find(iframe)?.groupValues?.get(1)
                        ?: return@forEach
                    val resolved = when {
                        host.contains("megaplay", ignoreCase = true) ->
                            resolveMegaPlay(iframe, label, subtitleCallback, callback)
                        host.contains("tryembed", ignoreCase = true) ->
                            resolveTryEmbed(iframe, label, subtitleCallback, callback)
                        host.contains("4animo", ignoreCase = true) ->
                            resolve4Animo(iframe, label, subtitleCallback, callback)
                        host.contains("vidhawk", ignoreCase = true) ->
                            resolveVidhawk(iframe, label, subtitleCallback, callback)
                        else -> loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                    }
                    if (resolved) found = true
                } catch (e: Exception) {
                    Log.d("RaghavAnime", "[Enma] $label failed: ${e.message}")
                }
            }
        }

        return found
    }

    private fun pageHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to EnmaDecryptor.USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to referer
    )

    private suspend fun emitLink(
        label: String,
        url: String,
        type: ExtractorLinkType,
        referer: String,
        extraHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = "Enma",
                name = label,
                url = url,
                type = type
            ) {
                this.referer = referer
                this.headers = mapOf("User-Agent" to EnmaDecryptor.USER_AGENT) + extraHeaders
            }
        )
    }

    private suspend fun emitSubtitles(
        root: com.google.gson.JsonObject,
        urlPrefix: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            root.getAsJsonArray("tracks")?.forEach { element ->
                val track = element.asJsonObject
                val kind = track.get("kind")?.asString ?: return@forEach
                if (kind != "captions" && kind != "subtitles") return@forEach
                val file = track.get("file")?.asString ?: return@forEach
                if (file.isBlank()) return@forEach
                val full = if (file.startsWith("http")) file else urlPrefix + file
                val label = track.get("label")?.asString ?: "English"
                subtitleCallback.invoke(newSubtitleFile(label, full) {
                    this.headers = headers
                })
            }
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Enma] subtitle tracks skipped: ${e.message}")
        }
    }

    private suspend fun fetchMegaPlaySources(url: String, referer: String): com.google.gson.JsonObject? {
        return try {
            val text = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to "https://megaplay.buzz",
                    "Referer" to referer
                )
            ).text
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Enma] MegaPlay sources request failed: ${e.message}")
            null
        }
    }

    private suspend fun megaPlayStreamUrl(root: com.google.gson.JsonObject?): String? {
        if (root == null) return null
        val enc = root.get("enc")?.takeIf { !it.isJsonNull }?.asString
        if (!enc.isNullOrBlank()) return MegaPlayCipher.resolveEncStreamUrl(enc, "https://megaplay.buzz")
        val sourcesEl = root.get("sources") ?: return null
        return when {
            sourcesEl.isJsonObject -> sourcesEl.asJsonObject.get("file")?.asString
            sourcesEl.isJsonArray && sourcesEl.asJsonArray.size() > 0 ->
                sourcesEl.asJsonArray[0].asJsonObject.get("file")?.asString
            else -> null
        }
    }

    private suspend fun resolveMegaPlay(
        iframeUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val sParam = Regex("""[?&]s=([^&]+)""").find(iframeUrl)?.groupValues?.get(1)
            val aniRoute = Regex("""/stream/ani/(\d+)/(\d+)/([a-z]+)""").find(iframeUrl)

            var root: com.google.gson.JsonObject? = null
            if (aniRoute != null) {
                val directUrl = "https://megaplay.buzz/stream/getSourcesNew" +
                    "?id=${aniRoute.groupValues[1]}&type=${aniRoute.groupValues[3]}" +
                    (sParam?.let { "&s=$it" } ?: "")
                root = fetchMegaPlaySources(directUrl, iframeUrl)
            }

            var m3u8 = megaPlayStreamUrl(root)
            if (m3u8 == null) {
                val pageHtml = app.get(iframeUrl, headers = pageHeaders("$mainUrl/")).text
                val streamId = Regex("""data-id=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""data-realid=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
                    ?: return false
                val sourcesUrl = "https://megaplay.buzz/stream/getSourcesNew?id=$streamId" +
                    (sParam?.let { "&s=$it" } ?: "")
                root = fetchMegaPlaySources(sourcesUrl, iframeUrl)
                m3u8 = megaPlayStreamUrl(root)
            }

            if (m3u8.isNullOrBlank() || root == null) return false

            emitLink(
                label,
                MegaPlayHelper.signUrl(m3u8),
                ExtractorLinkType.M3U8,
                "https://megaplay.buzz/",
                emptyMap(),
                callback
            )
            emitSubtitles(
                root,
                "",
                mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Referer" to "https://megaplay.buzz/"
                ),
                subtitleCallback
            )
            return true
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Enma] MegaPlay failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveTryEmbed(
        iframeUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val pageResponse = app.get(iframeUrl, headers = pageHeaders("$mainUrl/"))
            val html = pageResponse.text
            val payloadB64 = Regex("""RAW_PAYLOAD="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return false
            val nonce = Regex("""EMBED_NONCE="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return false
            val cookie = pageResponse.headers.values("Set-Cookie")
                .map { it.substringBefore(';') }
                .filter { it.contains('=') }
                .joinToString("; ")
            if (cookie.isBlank()) return false

            val meta = try {
                JsonParser.parseString(
                    String(android.util.Base64.decode(payloadB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                ).asJsonObject.getAsJsonObject("meta")
            } catch (e: Exception) {
                return false
            }
            val anilistId = meta.get("anilist_id")?.asString ?: return false
            val episode = meta.get("episode")?.asNumber?.toString() ?: return false
            val audio = meta.get("audio")?.asString ?: "sub"

            val streamUrl = "https://tryembed.us.cc/api/stream_data?id=$anilistId" +
                "&episode=$episode&audio=$audio&player=jw&nonce=$nonce"
            val streamText = app.get(
                streamUrl,
                headers = mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Accept" to "application/json",
                    "X-Embed-Nonce" to nonce,
                    "Referer" to iframeUrl,
                    "Cookie" to cookie
                )
            ).text

            val root = try {
                JsonParser.parseString(streamText).asJsonObject
            } catch (e: Exception) {
                return false
            }
            val providers = root.getAsJsonArray("providers") ?: return false
            var provider: com.google.gson.JsonObject? = null
            for (element in providers) {
                val p = element.asJsonObject
                val qualities = p.getAsJsonArray("qualities") ?: continue
                if (p.get("status")?.asString == "ready" && qualities.size() > 0) {
                    provider = p
                    break
                }
            }
            provider ?: return false

            val qualities = provider.getAsJsonArray("qualities") ?: return false
            if (qualities.size() == 0) return false
            val quality = qualities[0].asJsonObject
            val token = quality.get("token")?.asString
                ?: quality.get("fallbackToken")?.asString
                ?: return false
            val linkType = if (provider.get("type")?.asString == "mp4") {
                ExtractorLinkType.VIDEO
            } else {
                ExtractorLinkType.M3U8
            }
            val ext = if (linkType == ExtractorLinkType.VIDEO) "mp4" else "m3u8"
            val src = "https://tryembed.us.cc/s/$token.$ext"

            emitLink(
                label,
                src,
                linkType,
                "https://tryembed.us.cc/",
                mapOf("Cookie" to cookie),
                callback
            )

            try {
                provider.getAsJsonArray("captions")?.forEach { element ->
                    val cap = element.asJsonObject
                    val file = cap.get("url")?.asString ?: return@forEach
                    val capLabel = cap.get("label")?.asString ?: "English"
                    subtitleCallback.invoke(newSubtitleFile(capLabel, file))
                }
            } catch (e: Exception) {
                Log.d("RaghavAnime", "[Enma] TryEmbed captions skipped: ${e.message}")
            }
            return true
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Enma] TryEmbed failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolve4Animo(
        iframeUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val host = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return false

            // the sources token expires within seconds, so each retry reloads the page
            for (attempt in 0 until 3) {
                val html = try {
                    app.get(iframeUrl, headers = pageHeaders("$mainUrl/")).text
                } catch (e: Exception) {
                    Log.d("RaghavAnime", "[Enma] 4Animo page failed: ${e.message}")
                    return false
                }
                val sourcesPath = Regex("""__EMBED_SOURCES_URL__\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
                    ?: return false

                try {
                    val text = app.get(
                        host + sourcesPath,
                        headers = mapOf(
                            "User-Agent" to EnmaDecryptor.USER_AGENT,
                            "Accept" to "*/*",
                            "Referer" to iframeUrl,
                            "Origin" to host
                        )
                    ).text
                    val root = JsonParser.parseString(text).asJsonObject
                    val sourcesArr = root.getAsJsonArray("sources") ?: continue
                    if (sourcesArr.size() == 0) continue
                    val file = sourcesArr[0].asJsonObject.get("file")?.asString ?: continue
                    val m3u8 = if (file.startsWith("http")) file else host + file

                    emitLink(
                        label,
                        m3u8,
                        ExtractorLinkType.M3U8,
                        "$host/",
                        mapOf("Origin" to host),
                        callback
                    )
                    emitSubtitles(
                        root,
                        host,
                        mapOf(
                            "User-Agent" to EnmaDecryptor.USER_AGENT,
                            "Referer" to "$host/"
                        ),
                        subtitleCallback
                    )
                    return true
                } catch (e: Exception) {
                    Log.d("RaghavAnime", "[Enma] 4Animo attempt ${attempt + 1} failed: ${e.message}")
                }
                delay(1500L)
            }
            return false
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Enma] 4Animo failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveVidhawk(
        iframeUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val parts = Regex("""/embed/(?:ani|mal)/(\d+)/(\d+)/([a-z]+)""").find(iframeUrl)
                ?: return false
            val anilistId = parts.groupValues[1]
            val episode = parts.groupValues[2]
            val audio = parts.groupValues[3]

            val raceUrl = "https://vidhawk.buzz/api/stream/race?episode=$episode&audio=$audio" +
                "&server=flow&stream=1&anilistId=$anilistId"
            val raceText = app.get(
                raceUrl,
                headers = mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to "https://vidhawk.buzz/"
                )
            ).text

            var ticket: String? = null
            for (line in raceText.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val event = try {
                    JsonParser.parseString(trimmed).asJsonObject
                } catch (e: Exception) {
                    continue
                }
                if (event.get("type")?.asString == "done") {
                    ticket = event.get("ticket")?.asString
                    break
                }
                if (event.get("type")?.asString == "row") {
                    val row = event.getAsJsonObject("row")
                    if (row.get("ok")?.asBoolean == true && ticket == null) {
                        ticket = row.get("ticket")?.asString
                    }
                }
            }
            ticket ?: return false

            val playText = app.get(
                "https://vidhawk.buzz/api/play?t=" + URLEncoder.encode(ticket, "UTF-8"),
                headers = mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "https://vidhawk.buzz/"
                )
            ).text
            val play = try {
                JsonParser.parseString(playText).asJsonObject
            } catch (e: Exception) {
                return false
            }

            val tracks = play.getAsJsonArray("tracks") ?: return false
            var src: String? = null
            for (element in tracks) {
                val track = element.asJsonObject
                if (track.get("id")?.asString == audio) {
                    src = track.get("src")?.asString
                    break
                }
            }
            src ?: return false

            emitLink(
                label,
                src,
                ExtractorLinkType.M3U8,
                "https://vidhawk.buzz/",
                emptyMap(),
                callback
            )

            try {
                val captions = play.getAsJsonObject("captions")?.getAsJsonArray(audio)
                captions?.forEach { element ->
                    val cap = element.asJsonObject
                    val file = cap.get("src")?.asString ?: return@forEach
                    val capLabel = cap.get("label")?.asString ?: "English"
                    subtitleCallback.invoke(newSubtitleFile(capLabel, file) {
                        this.headers = mapOf(
                            "User-Agent" to EnmaDecryptor.USER_AGENT,
                            "Referer" to "https://vidhawk.buzz/"
                        )
                    })
                }
            } catch (e: Exception) {
                Log.d("RaghavAnime", "[Enma] VidHawk captions skipped: ${e.message}")
            }
            return true
        } catch (e: Exception) {
            Log.d("RaghavAnime", "[Enma] VidHawk failed: ${e.message}")
            return false
        }
    }
}
