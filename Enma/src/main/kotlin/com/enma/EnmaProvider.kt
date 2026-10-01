package com.enma

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
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.delay
import java.net.URLEncoder
import com.raghav.donation.DonationManager

class EnmaProvider : MainAPI() {
    override var mainUrl = "https://www.enma.lol"
    override var name = "Enma"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.enma.lol/api"

    private val cfKiller = CloudflareKiller()

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
        @JsonProperty("title") val title: String? = null,
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
        DonationManager.checkAndShow()
        val response = try {
            fetchApi("${request.data}?page=$page")
        } catch (e: Exception) {
            Log.e("Enma", "getMainPage ${request.name} fetch failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        } ?: return newHomePageResponse(request.name, emptyList())

        val items = try {
            parseJson<EnmaSearchResponse>(response).results?.data
        } catch (e: Exception) {
            Log.e("Enma", "getMainPage ${request.name} parse failed: ${e.message}")
            null
        }?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = try {
            fetchApi("$apiUrl/search?keyword=$encoded&page=1")
        } catch (e: Exception) {
            Log.e("Enma", "search fetch failed: ${e.message}")
            return emptyList()
        } ?: return emptyList()

        return try {
            parseJson<EnmaSearchResponse>(response).results?.data
        } catch (e: Exception) {
            Log.e("Enma", "search parse failed: ${e.message}")
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

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: url

        val infoText = try {
            fetchApi("$apiUrl/info?id=$animeId")
        } catch (e: Exception) {
            Log.e("Enma", "info fetch failed for $animeId: ${e.message}")
            return null
        } ?: return null

        val info = try {
            parseJson<EnmaInfoResponse>(infoText).results?.data
        } catch (e: Exception) {
            Log.e("Enma", "info parse failed for $animeId: ${e.message}")
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
            Log.e("Enma", "episodes fetch failed for $animeId: ${e.message}")
            return null
        } ?: return null

        val epsData = try {
            parseJson<EnmaEpisodesResponse>(epsText).results?.episodes
        } catch (e: Exception) {
            Log.e("Enma", "episodes parse failed for $animeId: ${e.message}")
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
            Log.e("Enma", "loadLinks got bad data: ${e.message}")
            return false
        }

        val servers = try {
            val serversText = fetchApi("$apiUrl/servers/${loadData.animeId}?ep=${loadData.episodeNum}")
            if (serversText.isNullOrBlank()) emptyList()
            else parseJson<EnmaServersResponse>(serversText).results ?: emptyList()
        } catch (e: Exception) {
            Log.e("Enma", "servers fetch failed for ${loadData.animeId} ep${loadData.episodeNum}: ${e.message}")
            emptyList()
        }

        val wantedTypes = if (loadData.type == "dub") listOf("dub") else listOf("sub", "hsub")
        val seenIframes = mutableSetOf<String>()
        var found = false

        for (groupType in wantedTypes) {
            val group = servers.filter { it.type == groupType }
            group.forEachIndexed { index, server ->
                val apiName = server.serverName?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val label = if (groupType == "hsub") "ENMA-${index + 1} hardsub" else "ENMA-${index + 1}"
                try {
                    val encodedId = URLEncoder.encode(loadData.episodeId, "UTF-8")
                    val streamText = fetchApi("$apiUrl/stream?id=$encodedId&server=$apiName&type=$groupType")
                        ?: return@forEachIndexed
                    val iframe = parseJson<EnmaStreamResponse>(streamText).results?.streamingLink?.iframe
                        ?: return@forEachIndexed
                    if (!seenIframes.add(iframe)) return@forEachIndexed

                    val host = Regex("""https?://([^/]+)""").find(iframe)?.groupValues?.get(1)
                        ?: return@forEachIndexed
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
                    Log.d("Enma", "$label failed: ${e.message}")
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
            Log.d("Enma", "subtitle tracks skipped: ${e.message}")
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
            Log.d("Enma", "MegaPlay sources request failed: ${e.message}")
            null
        }
    }

    private suspend fun megaPlayStreamUrl(root: com.google.gson.JsonObject?): String? {
        if (root == null) return null
        val enc = root.get("enc")?.takeIf { !it.isJsonNull }?.asString
        if (!enc.isNullOrBlank()) return EnmaCipher.decryptFile(enc)
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
                EnmaCipher.signUrl(m3u8),
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
            Log.d("Enma", "MegaPlay failed: ${e.message}")
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
            val host = "https://tryembed.us.cc"

            // the page hands out a single use bootstrap ticket and the nonce
            // it trades for only comes back to a request shaped exactly like
            // the player's own fetch, anything less is answered with a 403
            val pageResponse = app.get(iframeUrl, headers = pageHeaders("$mainUrl/"))
            val ticket = Regex("""BOOTSTRAP_TICKET="([^"]+)"""").find(pageResponse.text)?.groupValues?.get(1)
                ?: return false
            val pageCookies = pageResponse.headers.values("Set-Cookie")
                .map { it.substringBefore(';') }
                .filter { it.contains('=') }
            if (pageCookies.isEmpty()) return false

            val bootResponse = app.post(
                "$host/api/bootstrap",
                headers = mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Accept" to "*/*",
                    "X-TryEmbed-Bootstrap" to ticket,
                    "Cookie" to pageCookies.joinToString("; "),
                    "Origin" to host,
                    "Referer" to iframeUrl,
                    "sec-fetch-site" to "same-origin",
                    "sec-fetch-mode" to "cors",
                    "sec-fetch-dest" to "empty"
                )
            )
            if (!bootResponse.isSuccessful) return false
            val boot = try {
                JsonParser.parseString(bootResponse.text).asJsonObject
            } catch (e: Exception) {
                return false
            }
            val nonce = boot.get("embedNonce")?.takeIf { !it.isJsonNull }?.asString ?: return false
            val cookie = (pageCookies + bootResponse.headers.values("Set-Cookie").map { it.substringBefore(';') })
                .filter { it.contains('=') }
                .distinct()
                .joinToString("; ")

            val parts = Regex("""/embed/(?:anime|mal)/(\d+)/(\d+)/([a-z]+)""").find(iframeUrl)
                ?: return false
            val streamText = app.get(
                "$host/api/stream_data?id=${parts.groupValues[1]}" +
                    "&episode=${parts.groupValues[2]}&audio=${parts.groupValues[3]}&player=jw",
                headers = mapOf(
                    "User-Agent" to EnmaDecryptor.USER_AGENT,
                    "Accept" to "*/*",
                    "X-Embed-Nonce" to nonce,
                    "Cookie" to cookie,
                    "Origin" to host,
                    "Referer" to iframeUrl,
                    "sec-fetch-site" to "same-origin",
                    "sec-fetch-mode" to "cors",
                    "sec-fetch-dest" to "empty"
                )
            ).text

            val root = try {
                JsonParser.parseString(streamText).asJsonObject
            } catch (e: Exception) {
                return false
            }
            val providers = root.getAsJsonArray("providers") ?: return false
            var found = false
            for (element in providers) {
                val provider = element.asJsonObject
                if (provider.get("status")?.takeIf { !it.isJsonNull }?.asString != "ready") continue
                val qualities = provider.getAsJsonArray("qualities") ?: continue
                if (qualities.size() == 0) continue
                val quality = qualities[qualities.size() - 1].asJsonObject
                val direct = quality.get("directUrl")?.takeIf { !it.isJsonNull }?.asString
                val token = quality.get("token")?.takeIf { !it.isJsonNull }?.asString
                    ?: quality.get("fallbackToken")?.takeIf { !it.isJsonNull }?.asString
                if (direct.isNullOrBlank() && token.isNullOrBlank()) continue
                val isMp4 = provider.get("type")?.asString == "mp4"
                val src = direct?.takeIf { it.isNotBlank() }
                    ?: "$host/s/$token.${if (isMp4) "mp4" else "m3u8"}"
                val providerId = provider.get("id")?.takeIf { !it.isJsonNull }?.asString

                emitLink(
                    if (providerId.isNullOrBlank()) label else "$label $providerId",
                    src,
                    if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                    "$host/",
                    mapOf("Cookie" to cookie),
                    callback
                )
                found = true

                try {
                    provider.getAsJsonArray("captions")?.forEach { capElement ->
                        val cap = capElement.asJsonObject
                        val file = cap.get("url")?.asString ?: return@forEach
                        val capLabel = cap.get("label")?.asString ?: "English"
                        subtitleCallback.invoke(newSubtitleFile(capLabel, file) {
                            this.headers = mapOf(
                                "User-Agent" to EnmaDecryptor.USER_AGENT,
                                "Referer" to "$host/"
                            )
                        })
                    }
                } catch (e: Exception) {
                    Log.d("Enma", "TryEmbed captions skipped: ${e.message}")
                }
            }
            return found
        } catch (e: Exception) {
            Log.d("Enma", "TryEmbed failed: ${e.message}")
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
                    app.get(iframeUrl, headers = pageHeaders("$mainUrl/"), interceptor = cfKiller).text
                } catch (e: Exception) {
                    Log.d("Enma", "4Animo page failed: ${e.message}")
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
                        ),
                        interceptor = cfKiller
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
                    Log.d("Enma", "4Animo attempt ${attempt + 1} failed: ${e.message}")
                }
                delay(1500L)
            }
            return false
        } catch (e: Exception) {
            Log.d("Enma", "4Animo failed: ${e.message}")
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
                ),
                interceptor = cfKiller
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
                ),
                interceptor = cfKiller
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
                Log.d("Enma", "VidHawk captions skipped: ${e.message}")
            }
            return true
        } catch (e: Exception) {
            Log.d("Enma", "VidHawk failed: ${e.message}")
            return false
        }
    }
}
