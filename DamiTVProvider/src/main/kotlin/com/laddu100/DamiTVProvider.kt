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
        // The embed domain that serves as the legitimate referer for BunnyCDN
        private const val EMBED_DOMAIN = "https://embedindia.st"
    }

    data class FirebaseConfig(
        @JsonProperty("dami") val dami: String? = null,
        @JsonProperty("dami_url") val dami_url: String? = null,
        @JsonProperty("damiUrl") val damiUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            val url = json.dami ?: json.dami_url ?: json.damiUrl
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

    data class EventLoadData(
        val title: String,
        val url: String, // Stores the match/substream ID
        val posterUrl: String?,
        val category: String?,
        val status: String? = null,
        val date: Long? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String, // Stores the match/substream ID
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
        @JsonProperty("substreams") val substreams: List<DamiSubstream>?
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
            date = match.date
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
            date = match.date
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

    // ─�� Load ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        loadFirebaseUrl()
        val eventData = parseJson<EventLoadData>(url)
        val matchId = eventData.url
        val title = eventData.title
        val posterUrl = eventData.posterUrl
        val isUpcoming = eventData.status == "upcoming"
        val dateStr = formatMatchDate(eventData.date)

        val streamsList = mutableListOf<StreamInfo>()
        try {
            val text = app.get("$mainUrl/papi/extract-url/$matchId", headers = apiHeaders).text
            val response = parseJson<ExtractUrlResponse>(text)
            if (response.success) {
                val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
                streamsList.add(StreamInfo(name = mainStreamName, url = matchId))

                // Add substreams
                response.substreams?.forEach { sub ->
                    val localeSuffix = if (!sub.locale.isNullOrBlank()) " (${sub.locale})" else ""
                    val subName = if (isUpcoming) "${sub.name}$localeSuffix (Upcoming)" else "${sub.name}$localeSuffix"
                    streamsList.add(StreamInfo(name = subName, url = sub.id))
                }
            } else {
                val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
                streamsList.add(StreamInfo(name = mainStreamName, url = matchId))
            }
        } catch (e: Exception) {
            println("DamiTV: Load failed to query extract-url - ${e.message}")
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
                // Fetch fresh signed HLS URL from extract-url API right before playing
                val text = app.get("$mainUrl/papi/extract-url/${stream.url}", headers = apiHeaders).text
                val response = parseJson<ExtractUrlResponse>(text)
                if (response.success) {
                    // === PRIMARY: Direct HLS from BunnyCDN ===
                    if (!response.hlsUrl.isNullOrBlank()) {
                        // Use embedindia.st as referer — BunnyCDN whitelists this domain
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${stream.name} (Direct)",
                                url = response.hlsUrl,
                                referer = "$EMBED_DOMAIN/",
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
                            val embedHtml = app.get(
                                response.embedUrl,
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
                                        referer = "$EMBED_DOMAIN/",
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
                                                referer = "$EMBED_DOMAIN/",
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.headers = hlsPlayHeaders
                                            }
                                        )
                                        foundAny = true
                                    }
                                }
                            }
                        } catch (embedError: Exception) {
                            println("DamiTV: Embed extraction failed for ${stream.name} - ${embedError.message}")
                        }

                        // Also try loading via CloudStream's built-in extractors
                        try {
                            loadExtractor(response.embedUrl, "$mainUrl/", subtitleCallback, callback)
                            foundAny = true
                        } catch (extractError: Exception) {
                            println("DamiTV: loadExtractor failed for ${stream.name} - ${extractError.message}")
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
