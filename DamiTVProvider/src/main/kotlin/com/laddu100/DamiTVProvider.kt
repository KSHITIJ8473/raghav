package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Interceptor
import okhttp3.Response
import android.util.Base64

class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://dami-tv.pro"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private var isUrlLoaded = false

    companion object {
        var context: android.content.Context? = null
        // The embed domain that serves as the legitimate referer for BunnyCDN
        private const val EMBED_DOMAIN = "https://embedindia.st"
    }

    data class FirebaseConfig(
        @JsonProperty("dami") val dami: String? = null,
        @JsonProperty("dami_url") val dami_url: String? = null,
        @JsonProperty("damiUrl") val damiUrl: String? = null,
        @JsonProperty("damitv_url") val damitvUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            val url = json.dami ?: json.dami_url ?: json.damiUrl ?: json.damitvUrl
            url?.let {
                if (it.isNotEmpty()) {
                    mainUrl = it.removeSuffix("/")
                }
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            println("DamiTV: Failed to load Firebase URL - ${e.message}")
        }
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    // Headers for playing HLS streams from BunnyCDN
    // The CDN checks referer — embedindia.st is the whitelisted origin
    private val hlsPlayHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Referer" to "$EMBED_DOMAIN/",
            "Origin" to EMBED_DOMAIN,
            "Accept" to "*/*"
        )

    // ── Data classes for API JSON structures ───────────────────────────────────

    data class DamiTvChannel(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
    )

    data class DamiStreamedSource(
        @JsonProperty("source") val source: String,
        @JsonProperty("id") val id: String
    )

    data class DamiStreamVariant(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("hd") val hd: Boolean? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("source") val source: String,
        @JsonProperty("viewers") val viewers: Int? = null
    )

    data class EventLoadData(
        val title: String,
        val url: String, // Stores the match/substream ID
        val posterUrl: String?,
        val category: String?,
        val status: String? = null,
        val date: Long? = null,
        val isDaddyLive: Boolean? = null,
        val tvChannels: List<DamiTvChannel>? = null,
        val isStreamed: Boolean? = null,
        val streamedSources: List<DamiStreamedSource>? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String, // Stores the match/substream ID or custom streamed:// URI
        val headers: Map<String, String> = emptyMap()
    )

    data class DamiMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("league") val league: String?,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String?,
        @JsonProperty("date") val date: Long?,
        @JsonProperty("popular") val popular: Boolean?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?,
        @JsonProperty("embedUrl") val embedUrl: String?,
        @JsonProperty("substreams") val substreams: List<DamiSubstream>?,
        @JsonProperty("isDaddyLive") val isDaddyLive: Boolean?,
        @JsonProperty("tvChannels") val tvChannels: List<DamiTvChannel>?,
        @JsonProperty("isStreamed") val isStreamed: Boolean? = null,
        @JsonProperty("streamedSources") val streamedSources: List<DamiStreamedSource>? = null
    )

    data class DamiSubstream(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("locale") val locale: String?
    )

    data class ExtractUrlResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("hlsUrl") val hlsUrl: String?,
        @JsonProperty("embedUrl") val embedUrl: String?,
        @JsonProperty("matchId") val matchId: String?,
        @JsonProperty("substreams") val substreams: List<DamiSubstream>?,
        @JsonProperty("error") val error: String?
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null) return "soon"
        return try {
            val date = java.util.Date(timestamp)
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(date)
        } catch (e: Exception) {
            "soon"
        }
    }

    private fun matchToSearchResponse(match: DamiMatch): SearchResponse {
        val title = match.title
        val posterUrl = match.poster ?: ""
        val loadData = EventLoadData(
            title = title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            isDaddyLive = match.isDaddyLive,
            tvChannels = match.tvChannels,
            isStreamed = match.isStreamed,
            streamedSources = match.streamedSources
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun matchToUpcomingSearchResponse(match: DamiMatch): SearchResponse {
        val dateStr = formatMatchDate(match.date)
        val title = "${match.title} [Upcoming - Starts: $dateStr]"
        val posterUrl = match.poster ?: ""
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            isDaddyLive = match.isDaddyLive,
            tvChannels = match.tvChannels,
            isStreamed = match.isStreamed,
            streamedSources = match.streamedSources
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    // ── Main Page ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        loadFirebaseUrl()
        val lists = mutableListOf<HomePageList>()

        try {
            val allText = app.get("$mainUrl/papi/matches/all", headers = apiHeaders).text
            val allMatches = parseJson<List<DamiMatch>>(allText)

            // 1. Live Matches
            val liveMatches = allMatches.filter { match ->
                val status = match.status?.lowercase() ?: ""
                val cat = match.category?.lowercase() ?: ""
                status == "live" && cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
            }
            if (liveMatches.isNotEmpty()) {
                val items = liveMatches.map { matchToSearchResponse(it) }
                lists.add(HomePageList("🟢 Live Sports Events", items, isHorizontalImages = true))
            }

            // 2. Upcoming Matches
            val upcomingMatches = allMatches.filter { match ->
                val status = match.status?.lowercase() ?: ""
                val cat = match.category?.lowercase() ?: ""
                status == "upcoming" && cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
            }
            if (upcomingMatches.isNotEmpty()) {
                val items = upcomingMatches.map { matchToUpcomingSearchResponse(it) }
                lists.add(HomePageList("📅 Upcoming Matches (Live soon)", items, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            println("DamiTV: Failed to load matches - ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
        return try {
            val text = app.get("$mainUrl/papi/matches/all", headers = apiHeaders).text
            val allMatches = parseJson<List<DamiMatch>>(text)
            allMatches.filter { match ->
                val cat = match.category?.lowercase() ?: ""
                val isSport = cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
                isSport && (match.title.contains(query, ignoreCase = true) ||
                (match.league?.contains(query, ignoreCase = true) ?: false))
            }.map { match ->
                if (match.status == "upcoming") {
                    matchToUpcomingSearchResponse(match)
                } else {
                    matchToSearchResponse(match)
                }
            }
        } catch (e: Exception) {
            println("DamiTV: Search failed - ${e.message}")
            emptyList()
        }
    }

    // ── Load ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        loadFirebaseUrl()
        val eventData = parseJson<EventLoadData>(url)
        val matchId = eventData.url
        val title = eventData.title
        val posterUrl = eventData.posterUrl
        val isUpcoming = eventData.status == "upcoming"
        val dateStr = formatMatchDate(eventData.date)

        val streamsList = mutableListOf<StreamInfo>()

        // 1. Parse mapped TV channels directly if present
        if (!eventData.tvChannels.isNullOrEmpty()) {
            eventData.tvChannels.forEach { ch ->
                val chName = if (isUpcoming) "${ch.name} (Upcoming)" else ch.name
                // Stream URL points directly to DamiTV's DLHD proxy playlist
                val dlhdProxyUrl = "$mainUrl/papi/tv/dlhd/${ch.id}/playlist.m3u8"
                streamsList.add(StreamInfo(name = chName, url = dlhdProxyUrl))
            }
        }

        // 2. Query standard PPV API endpoints (Main Stream & Substreams)
        var addedPpvStreams = false
        try {
            val text = app.get("$mainUrl/papi/extract-url/$matchId", headers = apiHeaders).text
            val response = parseJson<ExtractUrlResponse>(text)
            if (response.success) {
                val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
                streamsList.add(StreamInfo(name = mainStreamName, url = matchId))
                addedPpvStreams = true

                // Add substreams
                response.substreams?.forEach { sub ->
                    val localeSuffix = if (!sub.locale.isNullOrBlank()) " (${sub.locale})" else ""
                    val subName = if (isUpcoming) "${sub.name}$localeSuffix (Upcoming)" else "${sub.name}$localeSuffix"
                    streamsList.add(StreamInfo(name = subName, url = sub.id))
                }
            }
        } catch (e: Exception) {
            println("DamiTV: Load failed to query extract-url - ${e.message}")
        }

        // 3. Handle streamed.pk sources
        var addedStreamedSources = false
        if (!eventData.streamedSources.isNullOrEmpty()) {
            val sdMulti = eventData.streamedSources.size > 1
            eventData.streamedSources.forEach { src ->
                try {
                    val streamUrl = "$mainUrl/papi/stream/${src.source}/${src.id}"
                    val streamText = app.get(streamUrl, headers = apiHeaders).text
                    val variants = parseJson<List<DamiStreamVariant>>(streamText)
                    variants.forEach { st ->
                        val sn = st.streamNo
                        val namePrefix = if (sdMulti) "${src.source.replaceFirstChar { it.uppercase() }} " else "Server "
                        val stName = "$namePrefix$sn"
                        
                        val encodedId = java.net.URLEncoder.encode(src.id, "UTF-8").replace("+", "%20")
                        val encodedFallback = st.embedUrl?.let { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
                        val customUrl = "streamed://${src.source}?id=$encodedId&num=$sn&fallback=$encodedFallback"
                        
                        streamsList.add(StreamInfo(name = stName, url = customUrl))
                        addedStreamedSources = true
                    }
                } catch (e: Exception) {
                    println("DamiTV: Failed to load streamed source - ${e.message}")
                }
            }
        }

        // 4. Ensure we have at least one fallback stream if nothing else was loaded
        if (!addedPpvStreams && !addedStreamedSources) {
            val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
            streamsList.add(StreamInfo(name = mainStreamName, url = matchId))
        }

        val streamData = StreamLoadData(title, streamsList)

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = posterUrl
            this.dataUrl = streamData.toJson()
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            println("DamiTV: loadLinks parse error — ${e.message}")
            return false
        }

        if (streamData.streams.isEmpty()) return false

        var foundAny = false

        streamData.streams.forEach { stream ->
            try {
                // 1. DLHD proxy streams
                if (stream.url.contains("/papi/tv/dlhd/")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = stream.name,
                            url = stream.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl
                            )
                        }
                    )
                    foundAny = true
                } 
                // 2. Streamed.pk streams
                else if (stream.url.startsWith("streamed://")) {
                    try {
                        val stripped = stream.url.substring("streamed://".length)
                        val queryIndex = stripped.indexOf('?')
                        val source = if (queryIndex != -1) stripped.substring(0, queryIndex) else stripped
                        val queryString = if (queryIndex != -1) stripped.substring(queryIndex + 1) else ""
                        
                        var streamId = ""
                        var streamNo = ""
                        var fallbackUrl = ""
                        
                        if (queryString.isNotEmpty()) {
                            val params = queryString.split('&')
                            for (param in params) {
                                val pair = param.split('=', limit = 2)
                                if (pair.size == 2) {
                                    when (pair[0]) {
                                        "id" -> streamId = java.net.URLDecoder.decode(pair[1], "UTF-8")
                                        "num" -> streamNo = java.net.URLDecoder.decode(pair[1], "UTF-8")
                                        "fallback" -> fallbackUrl = java.net.URLDecoder.decode(pair[1], "UTF-8")
                                    }
                                }
                            }
                        }

                        if (source.isNotEmpty() && streamId.isNotEmpty() && streamNo.isNotEmpty()) {
                            // Fetch sd-token
                            val tokenResponse = app.get("$mainUrl/papi/sd-token", headers = apiHeaders).text
                            val tokenData = parseJson<Map<String, Any>>(tokenResponse)
                            val token = tokenData["token"] as? String ?: ""
                            val tokenPath = tokenData["token_path"] as? String ?: ""
                            val expires = (tokenData["expires"] as? Number)?.toLong() ?: 0L

                            // URL encode all segments of the path
                            val encodedSource = java.net.URLEncoder.encode(source, "UTF-8").replace("+", "%20")
                            val encodedStreamId = java.net.URLEncoder.encode(streamId, "UTF-8").replace("+", "%20")
                            val encodedTokenPath = java.net.URLEncoder.encode(tokenPath, "UTF-8").replace("+", "%20")
                            
                            val hlsUrl = "https://damitvsd.b-cdn.net/live-sd/streamed/$encodedSource/$encodedStreamId/$streamNo/playlist.m3u8?token=$token&token_path=$encodedTokenPath&expires=$expires"

                            // Direct HLS Link
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${stream.name} (Direct)",
                                    url = hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = hlsPlayHeaders
                                }
                            )
                            foundAny = true

                            // Fallback extraction
                            if (fallbackUrl.isNotEmpty()) {
                                try {
                                    val isEmbedIndia = fallbackUrl.contains("embedindia.st", ignoreCase = true) || fallbackUrl.contains("embed.st", ignoreCase = true)
                                    if (isEmbedIndia && DamiTVProvider.context != null) {
                                        val extractor = EmbedIndiaExtractor(DamiTVProvider.context!!)
                                        extractor.getUrl(
                                            url = fallbackUrl,
                                            referer = "$mainUrl/",
                                            subtitleCallback = subtitleCallback,
                                            callback = callback
                                        )
                                        foundAny = true
                                    } else {
                                        val embedHtml = app.get(
                                            fallbackUrl,
                                            headers = mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
                                                "Referer" to "$mainUrl/"
                                            )
                                        ).text

                                        // Extract m3u8 matches from embed html
                                        val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                                        val m3u8Matches = m3u8Pattern.findAll(embedHtml)
                                        m3u8Matches.forEachIndexed { idx, match ->
                                            val m3u8Url = match.value
                                                .replace("\\u0026", "&")
                                                .replace("\\/", "/")
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = this.name,
                                                    name = "${stream.name} (Embed ${idx + 1})",
                                                    url = m3u8Url,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.headers = hlsPlayHeaders
                                                }
                                            )
                                            foundAny = true
                                        }

                                        // Also try built-in extractors
                                        loadExtractor(fallbackUrl, "$mainUrl/", subtitleCallback, callback)
                                        foundAny = true
                                    }
                                } catch (e: Exception) {
                                    println("DamiTV: Failed to load fallback stream for ${stream.name} - ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("DamiTV: Failed to load streamed link for ${stream.name} - ${e.message}")
                    }
                } 
                // 3. Standard PPV extraction
                else {
                    // Fetch fresh signed HLS URL from extract-url API right before playing
                    val text = app.get("$mainUrl/papi/extract-url/${stream.url}", headers = apiHeaders).text
                    val response = parseJson<ExtractUrlResponse>(text)
                    if (response.success) {
                        // === PRIMARY: Direct HLS from BunnyCDN ===
                        if (!response.hlsUrl.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${stream.name} (Direct)",
                                    url = response.hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = hlsPlayHeaders
                                }
                            )
                            foundAny = true
                        }

                        // === FALLBACK: Try embed page extraction ===
                        if (!response.embedUrl.isNullOrBlank()) {
                            try {
                                val embedUrl = response.embedUrl!!
                                val isEmbedIndia = embedUrl.contains("embedindia.st", ignoreCase = true) || embedUrl.contains("embed.st", ignoreCase = true)
                                if (isEmbedIndia && DamiTVProvider.context != null) {
                                    val extractor = EmbedIndiaExtractor(DamiTVProvider.context!!)
                                    extractor.getUrl(
                                        url = embedUrl,
                                        referer = "$mainUrl/",
                                        subtitleCallback = subtitleCallback,
                                        callback = callback
                                    )
                                    foundAny = true
                                } else {
                                    val embedHtml = app.get(
                                        embedUrl,
                                        headers = mapOf(
                                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
                                            "Referer" to "$mainUrl/"
                                        )
                                    ).text

                                    // Try to extract any m3u8 URLs from the embed page
                                    val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                                    val m3u8Matches = m3u8Pattern.findAll(embedHtml)
                                    m3u8Matches.forEachIndexed { idx, match ->
                                        val m3u8Url = match.value
                                            .replace("\\u0026", "&")
                                            .replace("\\/", "/")
                                        callback.invoke(
                                            newExtractorLink(
                                                source = this.name,
                                                name = "${stream.name} (Embed ${idx + 1})",
                                                url = m3u8Url,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.headers = hlsPlayHeaders
                                            }
                                        )
                                        foundAny = true
                                    }

                                    // Also try to find the stream URL in JavaScript variables
                                    val jsPatterns = listOf(
                                        Regex("""['"]?(hlsUrl|streamUrl|source|file|src)['"]?\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                                        Regex("""setStream\(['"]([^'"]+)['"]"""),
                                        Regex("""b-cdn\.net[^\s"']*\.m3u8[^\s"']*""")
                                    )
                                    for (pattern in jsPatterns) {
                                        pattern.findAll(embedHtml).forEach { jsMatch ->
                                            val url = if (jsMatch.groups.size > 2) {
                                                jsMatch.groups[2]?.value ?: jsMatch.value
                                            } else {
                                                jsMatch.value
                                            }
                                            if (url.contains(".m3u8") && !m3u8Matches.any { it.value == url }) {
                                                val cleanUrl = if (!url.startsWith("http")) "https://$url" else url
                                                callback.invoke(
                                                    newExtractorLink(
                                                        source = this.name,
                                                        name = "${stream.name} (JS)",
                                                        url = cleanUrl.replace("\\u0026", "&").replace("\\/", "/"),
                                                        type = ExtractorLinkType.M3U8
                                                    ) {
                                                        this.headers = hlsPlayHeaders
                                                    }
                                                )
                                                foundAny = true
                                            }
                                        }
                                    }

                                    // Also try loading via CloudStream's built-in extractors
                                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                                    foundAny = true
                                }
                            } catch (embedError: Exception) {
                                println("DamiTV: Embed extraction failed for ${stream.name} - ${embedError.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("DamiTV: Failed to load stream link for ${stream.name} - ${e.message}")
            }
        }

        return foundAny
    }
}
