package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://dami-tv.pro"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private var isUrlLoaded = false

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
            if (!url.isNullOrBlank()) {
                mainUrl = url.removeSuffix("/")
                isUrlLoaded = true
            }
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
    // The CDN checks referer — mainUrl is the whitelisted origin
    private val hlsPlayHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
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

    // Team metadata returned by /papi/matches/all (used for richer plot + search).
    data class DamiTeam(
        @JsonProperty("name") val name: String?,
        @JsonProperty("badge") val badge: String?
    )
    data class DamiTeams(
        @JsonProperty("home") val home: DamiTeam?,
        @JsonProperty("away") val away: DamiTeam?
    )

    data class EventLoadData(
        val title: String,
        val url: String, // Stores the match/substream ID
        val posterUrl: String?,
        val category: String?,
        val status: String? = null,
        val date: Long? = null,
        val league: String? = null,
        val teams: DamiTeams? = null,
        val isDaddyLive: Boolean? = null,
        val tvChannels: List<DamiTvChannel>? = null,
        val isStreamed: Boolean? = null,
        val sources: List<DamiStreamedSource>? = null,
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
        @JsonProperty("ppvPoster") val ppvPoster: String? = null,
        @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?,
        @JsonProperty("teams") val teams: DamiTeams? = null,
        @JsonProperty("embedUrl") val embedUrl: String?,
        @JsonProperty("substreams") val substreams: List<DamiSubstream>?,
        @JsonProperty("isDaddyLive") val isDaddyLive: Boolean?,
        @JsonProperty("tvChannels") val tvChannels: List<DamiTvChannel>?,
        @JsonProperty("isStreamed") val isStreamed: Boolean? = null,
        // The site renamed `streamedSources` -> `sources` in the matches API.
        // Read the new field first and keep the legacy one as a fallback for
        // older cached EventLoadData payloads.
        @JsonProperty("sources") val sources: List<DamiStreamedSource>? = null,
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

    // Fetch a fresh signed HLS URL from /papi/extract-url right before playback.
    // Retries once to absorb transient Cloudflare/network hiccups, which are a
    // common cause of intermittent playback failures on dami-tv.pro.
    private suspend fun fetchExtractUrl(id: String): ExtractUrlResponse? {
        repeat(2) { attempt ->
            try {
                val text = app.get("$mainUrl/papi/extract-url/$id", headers = apiHeaders).text
                val resp = parseJson<ExtractUrlResponse>(text)
                if (resp.success) return resp
            } catch (e: Exception) {
                println("DamiTV: extract-url attempt ${attempt + 1} failed for $id - ${e.message}")
            }
        }
        return null
    }

    private fun matchToSearchResponse(match: DamiMatch): SearchResponse {
        val title = match.title
        val posterUrl = match.poster ?: match.ppvPoster ?: match.teams?.home?.badge ?: ""
        val loadData = EventLoadData(
            title = title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            league = match.league,
            teams = match.teams,
            isDaddyLive = match.isDaddyLive,
            tvChannels = match.tvChannels,
            isStreamed = match.isStreamed,
            sources = match.sources ?: match.streamedSources,
            streamedSources = match.streamedSources
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun matchToUpcomingSearchResponse(match: DamiMatch): SearchResponse {
        val dateStr = formatMatchDate(match.date)
        val title = "${match.title} [Upcoming - Starts: $dateStr]"
        val posterUrl = match.poster ?: match.ppvPoster ?: match.teams?.home?.badge ?: ""
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            league = match.league,
            teams = match.teams,
            isDaddyLive = match.isDaddyLive,
            tvChannels = match.tvChannels,
            isStreamed = match.isStreamed,
            sources = match.sources ?: match.streamedSources,
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

            // 0. Popular Now (matches flagged popular=true by the site)
            val popular = allMatches.filter { it.popular == true }
            if (popular.isNotEmpty()) {
                val items = popular.map { matchToSearchResponse(it) }
                lists.add(HomePageList("🔥 Popular Now", items, isHorizontalImages = true))
            }

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

            // 3. 24/7 Channels & Live TV (previously filtered out — now surfaced)
            val channels = allMatches.filter { match ->
                val cat = match.category?.lowercase() ?: ""
                cat == "24/7-streams" || cat == "live-tv" || cat == "channels" || cat.contains("stream")
            }
            if (channels.isNotEmpty()) {
                val items = channels.map { matchToSearchResponse(it) }
                lists.add(HomePageList("📡 24/7 Channels & Live TV", items, isHorizontalImages = true))
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
                match.title.contains(query, ignoreCase = true) ||
                (match.league?.contains(query, ignoreCase = true) ?: false) ||
                (match.category?.contains(query, ignoreCase = true) ?: false) ||
                (match.teams?.home?.name?.contains(query, ignoreCase = true) ?: false) ||
                (match.teams?.away?.name?.contains(query, ignoreCase = true) ?: false)
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

        // 1. Parse mapped TV channels directly if present.
        // These resolve through DamiTV's DLHD proxy (/papi/tv/dlhd/<id>/playlist.m3u8)
        // which proxies the stream through dami-tv.pro itself — the MOST RELIABLE path
        // (the direct BunnyCDN hlsUrl is intermittently blocked, see loadLinks notes).
        if (!eventData.tvChannels.isNullOrEmpty()) {
            eventData.tvChannels.forEach { ch ->
                val chName = if (isUpcoming) "📺 ${ch.name} (Upcoming)" else "📺 ${ch.name}"
                val dlhdProxyUrl = "$mainUrl/papi/tv/dlhd/${ch.id}/playlist.m3u8"
                streamsList.add(StreamInfo(name = chName, url = dlhdProxyUrl))
            }
        }

        // 2. Query standard PPV API endpoints (Main Stream & Substreams)
        var addedPpvStreams = false
        try {
            val response = fetchExtractUrl(matchId)
            if (response != null) {
                val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream (Direct CDN)"
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
        // NOTE: dami-tv.pro renamed the `streamedSources` JSON field to `sources`.
        // Prefer the new field and fall back to the legacy one so old cached
        // detail-page payloads keep working.
        val streamedSourcesList = eventData.sources ?: eventData.streamedSources
        var addedStreamedSources = false
        if (!streamedSourcesList.isNullOrEmpty()) {
            val sdMulti = streamedSourcesList.size > 1
            streamedSourcesList.forEach { src ->
                try {
                    val streamUrl = "$mainUrl/papi/stream/${src.source}/${src.id}"
                    val streamText = app.get(streamUrl, headers = apiHeaders).text
                    val variants = parseJson<List<DamiStreamVariant>>(streamText)
                    variants.forEach { st ->
                        val sn = st.streamNo
                        val namePrefix = if (sdMulti) "${src.source.replaceFirstChar { it.uppercase() }} " else "Server "
                        // Append quality + language so users can pick the best server at a glance.
                        val quality = if (st.hd == true) "HD" else "SD"
                        val lang = st.language?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                        val stName = "$namePrefix$sn ($quality$lang)"
                        
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
            val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream (Direct CDN)"
            streamsList.add(StreamInfo(name = mainStreamName, url = matchId))
        }

        val streamData = StreamLoadData(title, streamsList)

        val plot = buildString {
            eventData.league?.takeIf { it.isNotBlank() }?.let { append("League: $it\n") }
            eventData.teams?.let { t ->
                t.home?.name?.takeIf { it.isNotBlank() }?.let { append("Home: $it\n") }
                t.away?.name?.takeIf { it.isNotBlank() }?.let { append("Away: $it\n") }
            }
            eventData.status?.takeIf { it.isNotBlank() }?.let {
                append("Status: ${it.replaceFirstChar { c -> c.uppercase() }}\n")
            }
            if (eventData.date != null) append("Starts: $dateStr")
        }.trim()

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = posterUrl
            this.plot = plot
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
                            val playHeaders = hlsPlayHeaders

                            // Direct BunnyCDN SD HLS via sd-token.
                            // NON-FATAL: dami-tv.pro removed/deprecated the /papi/sd-token
                            // endpoint (it now 404s and the site's web player no longer
                            // references damitvsd.b-cdn.net). A failure here MUST NOT abort
                            // the embedUrl fallback below, otherwise the whole server
                            // variant disappears from the playable stream list.
                            try {
                                val tokenResponse = app.get("$mainUrl/papi/sd-token", headers = apiHeaders).text
                                val tokenData = parseJson<Map<String, Any>>(tokenResponse)
                                val token = tokenData["token"] as? String ?: ""
                                if (token.isNotEmpty()) {
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
                                            this.headers = playHeaders
                                        }
                                    )
                                    foundAny = true
                                }
                            } catch (e: Exception) {
                                println("DamiTV: sd-token direct HLS unavailable for ${stream.name} - ${e.message}")
                            }

                            // Fallback: hand the variant's embedUrl to CloudStream's extractor
                            // registry. embedindia.st is JS-gated (a WASM module resolves
                            // the real m3u8 to vishnu.indianservers.st at runtime), so plain
                            // HTTP scraping cannot recover an m3u8 — only the registry is
                            // tried as a best-effort, non-fatal last resort.
                            if (fallbackUrl.isNotEmpty()) {
                                try {
                                    loadExtractor(fallbackUrl, "$mainUrl/", subtitleCallback, callback)
                                } catch (e: Exception) {
                                    println("DamiTV: loadExtractor failed for ${stream.name} - ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("DamiTV: Failed to load streamed link for ${stream.name} - ${e.message}")
                    }
                } 
                // 3. Standard PPV extraction
                else {
                    // Fetch a fresh signed HLS URL right before playing (with retry).
                    val response = fetchExtractUrl(stream.url)
                    if (response != null) {
                        val playHeaders = hlsPlayHeaders

                        // === PRIMARY: Direct HLS from BunnyCDN ===
                        if (!response.hlsUrl.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${stream.name} (Direct)",
                                    url = response.hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = playHeaders
                                }
                            )
                            foundAny = true
                        }

                        // === FALLBACK: hand embedUrl to CloudStream's extractor registry ===
                        // embedindia.st is a JS-rendered player: the real m3u8 is resolved at
                        // runtime (WASM -> vishnu.indianservers.st) and is NOT present in the
                        // HTML, so HTTP regex scraping cannot recover it. We only try the
                        // built-in extractor registry in case a future extractor supports it;
                        // any failure is non-fatal (the direct hlsUrl above is the real source).
                        if (!response.embedUrl.isNullOrBlank()) {
                            try {
                                loadExtractor(response.embedUrl, "$mainUrl/", subtitleCallback, callback)
                            } catch (extractError: Exception) {
                                println("DamiTV: loadExtractor failed for ${stream.name} - ${extractError.message}")
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
