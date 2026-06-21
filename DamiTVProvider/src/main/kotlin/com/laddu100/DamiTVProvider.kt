package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Interceptor
import okhttp3.Response

class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://dami-tv.pro"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // ── Data classes for API JSON structures ───────────────────────────────────

    data class EventLoadData(
        val title: String,
        val url: String, // Stores the match/substream ID
        val posterUrl: String?,
        val category: String?
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

    // ── Main Page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        // Live Sports Matches
        try {
            val liveText = app.get("$mainUrl/papi/matches/live", headers = baseHeaders).text
            val liveMatches = parseJson<List<DamiMatch>>(liveText)
            val filteredMatches = liveMatches.filter { match ->
                val cat = match.category?.lowercase() ?: ""
                cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
            }
            if (filteredMatches.isNotEmpty()) {
                val items = filteredMatches.map { matchToSearchResponse(it) }
                lists.add(HomePageList("🟢 Live Sports Events", items, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            println("DamiTV: Failed to load live matches - ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    private fun matchToSearchResponse(match: DamiMatch): SearchResponse {
        val title = match.title
        val posterUrl = match.poster ?: ""
        val loadData = EventLoadData(
            title = title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val text = app.get("$mainUrl/papi/matches/live", headers = baseHeaders).text
            val liveMatches = parseJson<List<DamiMatch>>(text)
            liveMatches.filter { match ->
                val cat = match.category?.lowercase() ?: ""
                val isSport = cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
                isSport && (match.title.contains(query, ignoreCase = true) ||
                (match.league?.contains(query, ignoreCase = true) ?: false))
            }.map { matchToSearchResponse(it) }
        } catch (e: Exception) {
            println("DamiTV: Search failed - ${e.message}")
            emptyList()
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val eventData = parseJson<EventLoadData>(url)
        val matchId = eventData.url
        val title = eventData.title
        val posterUrl = eventData.posterUrl

        val streamsList = mutableListOf<StreamInfo>()
        try {
            val text = app.get("$mainUrl/papi/extract-url/$matchId", headers = baseHeaders).text
            val response = parseJson<ExtractUrlResponse>(text)
            if (response.success) {
                // Add main stream
                streamsList.add(StreamInfo(name = "Main Stream", url = matchId))

                // Add substreams
                response.substreams?.forEach { sub ->
                    val localeSuffix = if (!sub.locale.isNullOrBlank()) " (${sub.locale})" else ""
                    streamsList.add(StreamInfo(name = "${sub.name}$localeSuffix", url = sub.id))
                }
            } else {
                streamsList.add(StreamInfo(name = "Main Stream", url = matchId))
            }
        } catch (e: Exception) {
            println("DamiTV: Load failed to query extract-url - ${e.message}")
            streamsList.add(StreamInfo(name = "Main Stream", url = matchId))
        }

        val streamData = StreamLoadData(title, streamsList)

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = posterUrl
            this.dataUrl = streamData.toJson()
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            println("DamiTV: loadLinks parse error — ${e.message}")
            return false
        }

        if (streamData.streams.isEmpty()) return false

        streamData.streams.forEach { stream ->
            try {
                // Fetch fresh signed HLS URL from extract-url API right before playing
                val text = app.get("$mainUrl/papi/extract-url/${stream.url}", headers = baseHeaders).text
                val response = parseJson<ExtractUrlResponse>(text)
                if (response.success) {
                    if (!response.hlsUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(name, stream.name, response.hlsUrl, ExtractorLinkType.M3U8) {
                                this.quality = Qualities.Unknown.value
                                this.referer = "$mainUrl/"
                            }
                        )
                    }
                    if (!response.embedUrl.isNullOrBlank()) {
                        loadExtractor(response.embedUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                println("DamiTV: Failed to load stream link for ${stream.name} - ${e.message}")
            }
        }

        return true
    }
}