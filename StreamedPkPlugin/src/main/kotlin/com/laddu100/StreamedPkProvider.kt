package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class StreamedPkProvider : MainAPI() {

    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed.pk"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private var damiUrl = "https://dami-tv.pro"
    private var isUrlLoaded = false

    data class FirebaseConfig(
        @JsonProperty("damitv_url") val damitvUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            json.damitvUrl?.let {
                if (it.isNotEmpty()) {
                    damiUrl = it.removeSuffix("/")
                }
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            println("StreamedPk: Failed to load Firebase URL - ${e.message}")
        }
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    private val hlsPlayHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Referer" to "$damiUrl/",
            "Origin" to damiUrl,
            "Accept" to "*/*"
        )

    // Thread-safe DNS cache to prevent redundant resolutions
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    // Hardcoded IP mappings for domains blocked by ISP DNS
    private val fallbacks = mapOf(
        "streamed.pk" to listOf("185.178.208.164")
    )

    // Custom DNS resolver supporting fallback to Cloudflare DNS-over-HTTPS (1.1.1.1)
    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "1.1.1.1") {
                return listOf(InetAddress.getByName("1.1.1.1"))
            }

            // 1. Check cache first
            dnsCache[hostname]?.let { return it }

            // 2. Check hardcoded fallbacks
            fallbacks[hostname]?.let { ips ->
                val inetAddresses = ips.map { InetAddress.getByName(it) }
                dnsCache[hostname] = inetAddresses
                return inetAddresses
            }

            // 3. Try dynamic DoH resolution first to bypass ISP DNS hijacking
            val resolved = resolveDnsDoH(hostname)
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = resolved
                return resolved
            }

            // 4. Fallback to standard System DNS
            try {
                val systemResolved = Dns.SYSTEM.lookup(hostname)
                if (systemResolved.isNotEmpty()) {
                    dnsCache[hostname] = systemResolved
                    return systemResolved
                }
            } catch (e: Exception) {
                // System DNS failed/blocked
            }

            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val dnsClient by lazy {
        app.baseClient.newBuilder()
            .dns(customDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun resolveDnsDoH(hostname: String): List<InetAddress> {
        try {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseText = response.body?.string() ?: ""
                    val ipRegex = """\"data\"\s*:\s*\"([0-9.]+)\"""".toRegex()
                    val ips = ipRegex.findAll(responseText).map { it.groupValues[1] }.toList()
                    if (ips.isNotEmpty()) {
                        return ips.map { InetAddress.getByName(it) }
                    }
                }
            }
        } catch (e: Exception) {
            println("StreamedPk: DoH resolution failed for $hostname - ${e.message}")
        }
        return emptyList()
    }

    private fun customGet(path: String): String {
        val url = if (path.startsWith("http")) path else "$mainUrl$path"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .header("Referer", "$mainUrl/")
            .header("Origin", mainUrl)
            .build()
        
        dnsClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    // ── Data classes for API structures ──────────────────────────────────────────

    data class StreamedMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String?,
        @JsonProperty("date") val date: Long?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("popular") val popular: Boolean? = null,
        @JsonProperty("finished") val finished: Boolean? = null,
        @JsonProperty("sources") val sources: List<MatchSource>? = null
    )

    data class MatchSource(
        @JsonProperty("source") val source: String,
        @JsonProperty("id") val id: String
    )

    data class StreamVariant(
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
        val id: String,
        val posterUrl: String?,
        val date: Long?,
        val category: String?,
        val sources: List<MatchSource>?
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String
    )

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun getCategoryTitle(cat: String?): String {
        val clean = cat?.lowercase() ?: ""
        return when (clean) {
            "football" -> "⚽ Football (Upcoming)"
            "basketball" -> "🏀 Basketball (Upcoming)"
            "american-football" -> "🏈 American Football (Upcoming)"
            "hockey" -> "🏒 Hockey (Upcoming)"
            "baseball" -> "⚾ Baseball (Upcoming)"
            "motor-sports" -> "🏎️ Motor Sports (Upcoming)"
            "fight" -> "🥊 Fight (UFC, Boxing) (Upcoming)"
            "tennis" -> "🎾 Tennis (Upcoming)"
            "rugby" -> "🏉 Rugby (Upcoming)"
            "golf" -> "⛳ Golf (Upcoming)"
            "billiards" -> "🎱 Billiards (Upcoming)"
            "afl" -> "🏉 AFL (Upcoming)"
            "darts" -> "🎯 Darts (Upcoming)"
            "cricket" -> "🏏 Cricket (Upcoming)"
            "other" -> "🏆 Other Sports (Upcoming)"
            else -> "🏆 ${clean.replaceFirstChar { it.uppercase() }} (Upcoming)"
        }
    }

    private fun getPosterForMatch(category: String?, poster: String?): String {
        if (!poster.isNullOrBlank()) {
            return if (poster.startsWith("/")) "$mainUrl$poster" else poster
        }
        val clean = category?.lowercase() ?: ""
        return when (clean) {
            "football" -> "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=500"
            "basketball" -> "https://images.unsplash.com/photo-1546519638-68e109498ffc?w=500"
            "american-football" -> "https://images.unsplash.com/photo-1587280501635-68a0e82cd5ff?w=500"
            "hockey" -> "https://images.unsplash.com/photo-1515703407324-5f753eed2411?w=500"
            "baseball" -> "https://images.unsplash.com/photo-1530541930197-ff16ac917b0e?w=500"
            "motor-sports" -> "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=500"
            "fight" -> "https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=500"
            "tennis" -> "https://images.unsplash.com/photo-1595435934249-5df7ed86e1c0?w=500"
            "rugby" -> "https://images.unsplash.com/photo-1534353436294-0dbd4bdac845?w=500"
            "cricket" -> "https://images.unsplash.com/photo-1531415074968-036ba1b575da?w=500"
            else -> "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=500"
        }
    }

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "soon"
        return try {
            val date = java.util.Date(timestamp)
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(date)
        } catch (e: Exception) {
            "soon"
        }
    }

    // ── Main Page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        loadFirebaseUrl()
        val lists = mutableListOf<HomePageList>()

        try {
            val text = customGet("/api/matches/all")
            val allMatches = parseJson<List<StreamedMatch>>(text)

            // 1. Live Matches (sources list is not empty)
            val liveMatches = allMatches.filter { !it.sources.isNullOrEmpty() }
            if (liveMatches.isNotEmpty()) {
                val liveItems = liveMatches.map { match ->
                    val posterUrl = getPosterForMatch(match.category, match.poster)
                    val loadData = EventLoadData(
                        title = match.title,
                        id = match.id,
                        posterUrl = posterUrl,
                        date = match.date,
                        category = match.category,
                        sources = match.sources
                    )
                    newLiveSearchResponse("[LIVE] ${match.title}", loadData.toJson(), TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                }
                lists.add(HomePageList("🟢 Live Matches", liveItems, isHorizontalImages = true))
            }

            // 2. Upcoming Matches (sources list is empty)
            val upcomingMatches = allMatches.filter { it.sources.isNullOrEmpty() }
            if (upcomingMatches.isNotEmpty()) {
                val upcomingItems = upcomingMatches.map { match ->
                    val posterUrl = getPosterForMatch(match.category, match.poster)
                    val loadData = EventLoadData(
                        title = match.title,
                        id = match.id,
                        posterUrl = posterUrl,
                        date = match.date,
                        category = match.category,
                        sources = match.sources
                    )
                    val dateStr = formatMatchDate(match.date)
                    newLiveSearchResponse("${match.title} [Starts: $dateStr]", loadData.toJson(), TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                }
                lists.add(HomePageList("📅 Upcoming Matches", upcomingItems, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            println("StreamedPk: Failed to load main page - ${e.message}")
        }

        if (lists.isEmpty()) {
            val dummyLoadData = EventLoadData(
                title = "No matches available",
                id = "dummy",
                posterUrl = "",
                date = null,
                category = "other",
                sources = null
            )
            val dummyItem = newLiveSearchResponse(
                name = "No matches available right now. Please check back later!",
                url = dummyLoadData.toJson(),
                type = TvType.Live
            )
            lists.add(HomePageList("Matches Status", listOf(dummyItem), isHorizontalImages = true))
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
        val results = mutableListOf<SearchResponse>()
        try {
            val text = customGet("/api/matches/all")
            val allMatches = parseJson<List<StreamedMatch>>(text)
            allMatches.filter { match ->
                match.title.contains(query, ignoreCase = true) ||
                (match.category?.contains(query, ignoreCase = true) ?: false)
            }.forEach { match ->
                val posterUrl = getPosterForMatch(match.category, match.poster)
                val isLive = !match.sources.isNullOrEmpty()
                val loadData = EventLoadData(
                    title = match.title,
                    id = match.id,
                    posterUrl = posterUrl,
                    date = match.date,
                    category = match.category,
                    sources = match.sources
                )
                val displayTitle = if (isLive) "[LIVE] ${match.title}" else "${match.title} [Starts: ${formatMatchDate(match.date)}]"
                results.add(
                    newLiveSearchResponse(displayTitle, loadData.toJson(), TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        } catch (e: Exception) {
            println("StreamedPk: Search failed - ${e.message}")
        }
        return results
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        loadFirebaseUrl()
        val eventData = parseJson<EventLoadData>(url)
        val title = eventData.title
        var posterUrl = eventData.posterUrl
        var sources = eventData.sources
        var dateVal = eventData.date

        val streamsList = mutableListOf<StreamInfo>()

        if (eventData.id == "dummy") {
            return newLiveStreamLoadResponse(title, url, this.name) {
                this.posterUrl = posterUrl
                this.plot = "There are no live or scheduled matches on Streamed.pk at the moment. Please check back later!"
                this.dataUrl = StreamLoadData(title, emptyList()).toJson()
            }
        }

        // Fetch fresh match details to get active sources dynamically if match was scheduled
        try {
            val text = customGet("/api/matches/all")
            val allMatches = parseJson<List<StreamedMatch>>(text)
            val freshMatch = allMatches.find { it.id == eventData.id }
            if (freshMatch != null) {
                sources = freshMatch.sources
                dateVal = freshMatch.date
                posterUrl = getPosterForMatch(freshMatch.category, freshMatch.poster)
            }
        } catch (e: Exception) {
            println("StreamedPk: Failed to fetch fresh match details in load - ${e.message}")
        }

        val isUpcoming = sources.isNullOrEmpty()
        val dateStr = formatMatchDate(dateVal)

        // Fetch stream details for each source
        if (!sources.isNullOrEmpty()) {
            sources.forEach { src ->
                try {
                    val streamUrl = "/api/stream/${src.source}/${src.id}"
                    val streamText = customGet(streamUrl)
                    val variants = parseJson<List<StreamVariant>>(streamText)
                    
                    variants.forEach { st ->
                        val sn = st.streamNo
                        val langSuffix = if (!st.language.isNullOrBlank()) " (${st.language})" else ""
                        val hdSuffix = if (st.hd == true) " [HD]" else ""
                        val serverName = "Server $sn - ${src.source.uppercase()}$langSuffix$hdSuffix"
                        
                        val encodedId = java.net.URLEncoder.encode(src.id, "UTF-8").replace("+", "%20")
                        val encodedFallback = st.embedUrl?.let { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
                        val customUrl = "streamed://${src.source}?id=$encodedId&num=$sn&fallback=$encodedFallback"
                        
                        streamsList.add(StreamInfo(name = serverName, url = customUrl))
                    }
                } catch (e: Exception) {
                    println("StreamedPk: Failed to load stream details for ${src.source}:${src.id} - ${e.message}")
                }
            }
        }

        // If upcoming or no streams resolved, add placeholder
        if (streamsList.isEmpty()) {
            val serverName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "No stream link active yet"
            streamsList.add(StreamInfo(name = serverName, url = "upcoming://${eventData.id}"))
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
        loadFirebaseUrl()
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            println("StreamedPk: loadLinks parse error - ${e.message}")
            return false
        }

        if (streamData.streams.isEmpty()) return false

        var foundAny = false

        streamData.streams.forEach { stream ->
            try {
                if (stream.url.startsWith("upcoming://")) {
                    return@forEach
                }

                if (stream.url.startsWith("streamed://")) {
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
                        // 1. Reconstruct DIRECT HLS URL with signed token from DamiTV
                        try {
                            val tokenResponse = app.get("$damiUrl/papi/sd-token", headers = apiHeaders).text
                            val tokenData = parseJson<Map<String, Any>>(tokenResponse)
                            val token = tokenData["token"] as? String ?: ""
                            val tokenPath = tokenData["token_path"] as? String ?: ""
                            val expires = (tokenData["expires"] as? Number)?.toLong() ?: 0L

                            if (token.isNotEmpty() && tokenPath.isNotEmpty()) {
                                val encodedSource = java.net.URLEncoder.encode(source, "UTF-8").replace("+", "%20")
                                val encodedStreamId = java.net.URLEncoder.encode(streamId, "UTF-8").replace("+", "%20")
                                val encodedTokenPath = java.net.URLEncoder.encode(tokenPath, "UTF-8").replace("+", "%20")
                                
                                val hlsUrl = "https://damitvsd.b-cdn.net/live-sd/streamed/$encodedSource/$encodedStreamId/$streamNo/playlist.m3u8?token=$token&token_path=$encodedTokenPath&expires=$expires"

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
                            }
                        } catch (e: Exception) {
                            println("StreamedPk: Direct HLS link resolution failed for ${stream.name} - ${e.message}")
                        }

                        // 2. Extract links from embedUrl as fallback
                        if (fallbackUrl.isNotEmpty()) {
                            try {
                                val embedHtml = app.get(
                                    fallbackUrl,
                                    headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
                                        "Referer" to "$mainUrl/"
                                    )
                                ).text

                                // Extract m3u8 URLs from page
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

                                // Extract from Javascript stream config variables
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

                                // Load via built-in extractors
                                loadExtractor(fallbackUrl, "$mainUrl/", subtitleCallback, callback)
                                foundAny = true
                            } catch (e: Exception) {
                                println("StreamedPk: Fallback embed extraction failed for ${stream.name} - ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("StreamedPk: Failed to resolve links for ${stream.name} - ${e.message}")
            }
        }

        return foundAny
    }
}
