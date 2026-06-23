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
import kotlinx.coroutines.delay

class StreamEastProvider : MainAPI() {

    override var mainUrl = "https://istreameast.app"
    override var name = "StreamEast"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private var isUrlLoaded = false

    data class FirebaseConfig(
        @JsonProperty("streameast") val streameast: String? = null,
        @JsonProperty("streameast_url") val streameast_url: String? = null,
        @JsonProperty("streameastUrl") val streameastUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            val url = json.streameast ?: json.streameast_url ?: json.streameastUrl
            url?.let {
                if (it.isNotEmpty()) {
                    mainUrl = it.removeSuffix("/")
                }
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            println("StreamEast: Failed to load Firebase URL - ${e.message}")
        }
    }

    // Thread-safe DNS cache to prevent redundant resolutions
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    // Hardcoded IP mappings for domains blocked by ISP DNS
    private val fallbacks = mapOf(
        "istreameast.app" to listOf("128.0.104.20"),
        "gooz.aapmains.net" to listOf("95.214.234.151"),
        "chatgpt.hereisman.net" to listOf("95.214.234.151"),
        "grok.hereisman.net" to listOf("185.254.197.14"),
        "grok2.hereisman.net" to listOf("185.254.197.14")
    )

    // Custom DNS resolver supporting fallback to Cloudflare DNS-over-HTTPS (1.1.1.1)
    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "1.1.1.1") {
                return listOf(InetAddress.getByName("1.1.1.1"))
            }

            // 1. Check cache first (0ms overhead)
            dnsCache[hostname]?.let { return it }

            // 2. Check hardcoded fallbacks (0ms overhead)
            fallbacks[hostname]?.let { ips ->
                val inetAddresses = ips.map { InetAddress.getByName(it) }
                dnsCache[hostname] = inetAddresses
                return inetAddresses
            }

            // 3. Try dynamic DoH resolution first to bypass ISP DNS hijacking of streaming domains
            val resolved = resolveDnsDoH(hostname)
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = resolved
                return resolved
            }

            // 4. Fallback to standard System DNS lookup (e.g. for safe domains where DoH failed/blocked)
            try {
                val systemResolved = Dns.SYSTEM.lookup(hostname)
                if (systemResolved.isNotEmpty()) {
                    dnsCache[hostname] = systemResolved
                    return systemResolved
                }
            } catch (e: Exception) {
                // System DNS failed or blocked
            }

            // 5. Final fallback to System DNS
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    // Dedicated client for DNS-over-HTTPS (DoH) requests, using short timeouts to prevent blocking UI thread
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // Custom OkHttpClient delegating to CloudStream's global baseClient but with overridden DNS
    private val dnsClient by lazy {
        app.baseClient.newBuilder()
            .dns(customDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ── Helper to resolve IPs dynamically using Cloudflare DoH (directly via 1.1.1.1 IP) ────
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
            println("StreamEast: DoH resolution failed for $hostname - ${e.message}")
        }

        return emptyList()
    }

    // Scraper helper utilizing the custom client
    private fun customGet(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
        if (referer != null) {
            builder.header("Referer", referer)
        }
        val request = builder.build()
        dnsClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    private suspend fun customGetWithRetry(
        url: String,
        referer: String? = null,
        maxAttempts: Int = 4,
        validate: (String) -> Boolean = { true }
    ): String {
        var attempts = 0
        while (attempts < maxAttempts) {
            try {
                val html = customGet(url, referer)
                if (validate(html)) {
                    return html
                }
                println("StreamEast: Validation failed on attempt ${attempts + 1} for $url")
            } catch (e: Exception) {
                println("StreamEast: Attempt ${attempts + 1} failed for $url - ${e.message}")
            }
            attempts++
            if (attempts < maxAttempts) {
                delay(1500)
            }
        }
        return customGet(url, referer) // Final attempt, throw original error if still fails
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun stripHtml(html: String): String {
        val clean = html.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
        return decodeHtmlEntities(clean)
    }

    private fun getPosterForEvent(url: String, title: String): String {
        val combined = "$url $title".lowercase()
        return when {
            combined.contains("mlb") || combined.contains("baseball") -> "https://images.unsplash.com/photo-1530541930197-ff16ac917b0e?q=80&w=500"
            combined.contains("nba") || combined.contains("wnba") || combined.contains("basketball") || combined.contains("ncaab") -> "https://images.unsplash.com/photo-1546519638-68e109498ffc?q=80&w=500"
            combined.contains("nfl") || combined.contains("cfb") || combined.contains("american football") || combined.contains("ncaa") -> "https://images.unsplash.com/photo-1587280501635-68a0e82cd5ff?q=80&w=500"
            combined.contains("nhl") || combined.contains("hockey") -> "https://images.unsplash.com/photo-1515523110800-9415d13b84a8?q=80&w=500"
            combined.contains("ufc") || combined.contains("mma") || combined.contains("boxing") || combined.contains("fight") -> "https://images.unsplash.com/photo-1549719386-74dfcbf7dbed?q=80&w=500"
            combined.contains("f1") || combined.contains("formula") || combined.contains("motorsport") || combined.contains("gp") -> "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?q=80&w=500"
            combined.contains("world cup") || combined.contains("worldcup") || combined.contains("fifa") -> "https://images.unsplash.com/photo-1518091043644-c1d4457512c6?q=80&w=500"
            combined.contains("soccer") || combined.contains("football") || combined.contains("cup") || combined.contains("laliga") || combined.contains("premier") -> "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?q=80&w=500"
            else -> "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?q=80&w=500" // Generic sports fallback
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class EventLoadData(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val isLive: Boolean
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String // Represents stream ID
    )

    // ── Main Page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        loadFirebaseUrl()
        val lists = mutableListOf<HomePageList>()
        val liveItems = mutableListOf<SearchResponse>()
        val upcomingItems = mutableListOf<SearchResponse>()

        try {
            val html = customGetWithRetry("$mainUrl/v52") { it.contains("f1-podium--link") }
            
            // Extract event links matching f1-podium--link class in a robust way
            val aTagRegex = """<a\s+([^>]+)>(.*?)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val hrefRegex = """href="([^"]+)"""".toRegex()
            val timeRegex = """(\d+)\s+(minute|hour|day)[s]?\s+from\s+now""".toRegex(RegexOption.IGNORE_CASE)

            aTagRegex.findAll(html).forEach { match ->
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]

                if (attrs.contains("f1-podium--link")) {
                    val hrefMatch = hrefRegex.find(attrs)
                    val href = hrefMatch?.groupValues?.get(1) ?: return@forEach
                    val cleanUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
                    
                    val cleanText = stripHtml(body)

                    if (cleanText.isNotBlank()) {
                        var isLive = false
                        var title = cleanText
                        var shortTime = ""

                        if (cleanText.endsWith(" LIVE", ignoreCase = true)) {
                            isLive = true
                            title = cleanText.substring(0, cleanText.length - 5).trim()
                        } else {
                            val timeMatch = timeRegex.find(cleanText)
                            if (timeMatch != null) {
                                val num = timeMatch.groupValues[1]
                                val unit = timeMatch.groupValues[2].lowercase()[0]
                                shortTime = "$num$unit"
                                title = cleanText.substring(0, timeMatch.range.first).trim()
                            } else if (cleanText.contains(" LIVE", ignoreCase = true)) {
                                isLive = true
                                title = cleanText.replace(" LIVE", "", ignoreCase = true).trim()
                            }
                        }

                        val displayTitle = if (isLive) {
                            "[LIVE] $title"
                        } else {
                            if (shortTime.isNotEmpty()) "[UPCOMING - $shortTime] $title" else "[UPCOMING] $title"
                        }

                        val poster = getPosterForEvent(cleanUrl, title)
                        val loadData = EventLoadData(
                            title = title,
                            url = cleanUrl,
                            posterUrl = poster,
                            isLive = isLive
                        )

                        val searchRes = newLiveSearchResponse(displayTitle, loadData.toJson(), TvType.Live) {
                            this.posterUrl = poster
                        }

                        if (isLive) {
                            liveItems.add(searchRes)
                        } else {
                            upcomingItems.add(searchRes)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("StreamEast: Failed to load main page - ${e.message}")
        }

        if (liveItems.isNotEmpty()) {
            lists.add(HomePageList("🟢 StreamEast - Live Games", liveItems, isHorizontalImages = true))
        }
        if (upcomingItems.isNotEmpty()) {
            lists.add(HomePageList("📅 StreamEast - Upcoming Games", upcomingItems, isHorizontalImages = true))
        }

        if (lists.isEmpty()) {
            val dummyLoadData = EventLoadData(
                title = "No live matches right now",
                url = "dummy",
                posterUrl = "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?q=80&w=500",
                isLive = false
            )
            val dummyItem = newLiveSearchResponse(
                name = "No live matches right now",
                url = dummyLoadData.toJson(),
                type = TvType.Live
            ) {
                this.posterUrl = dummyLoadData.posterUrl
            }
            lists.add(HomePageList("No live matches right now. Please check back later!", listOf(dummyItem), isHorizontalImages = true))
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
        val results = mutableListOf<SearchResponse>()
        try {
            val mainPage = getMainPage(1, MainPageRequest(this.name, "", true))
            mainPage.items.forEach { list ->
                list.list.forEach { card ->
                    if (card.name.contains(query, ignoreCase = true)) {
                        results.add(card)
                    }
                }
            }
        } catch (e: Exception) {
            println("StreamEast: Search failed - ${e.message}")
        }
        return results
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        loadFirebaseUrl()
        val eventData = parseJson<EventLoadData>(url)
        val eventUrl = eventData.url
        val title = eventData.title

        if (eventUrl == "dummy") {
            return newLiveStreamLoadResponse(title, url, this.name) {
                this.posterUrl = eventData.posterUrl
                this.plot = "There are no live StreamEast streams scheduled at the moment. Please check back later when a match starts!"
                this.dataUrl = StreamLoadData(title, emptyList()).toJson()
            }
        }

        val streamsList = mutableListOf<StreamInfo>()
        try {
            val html = customGetWithRetry(eventUrl) { it.contains("streamId") || it.contains("stream-btn") }

            // Extract alternate buttons
            val btnRegex = """id="stream-btn-(\d+)"[^>]*onclick="[^"]*changeStream\(\1\)"[^>]*>\s*([^<]+)""".toRegex(RegexOption.IGNORE_CASE)
            val btnMatches = btnRegex.findAll(html)
            btnMatches.forEach {
                val streamId = it.groupValues[1]
                val name = it.groupValues[2].trim()
                streamsList.add(StreamInfo(name = name, url = streamId))
            }

            // Fallback to default streamId defined in script block
            if (streamsList.isEmpty()) {
                val defaultStreamMatch = """streamId\s*=\s*(\d+)""".toRegex().find(html)
                val defaultStreamId = defaultStreamMatch?.groupValues?.get(1)
                if (defaultStreamId != null) {
                    streamsList.add(StreamInfo(name = "Server 1", url = defaultStreamId))
                }
            }
        } catch (e: Exception) {
            println("StreamEast: Load failed to query event page - ${e.message}")
        }

        val streamData = StreamLoadData(title, streamsList)

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = eventData.posterUrl
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
            println("StreamEast: loadLinks parse error - ${e.message}")
            return false
        }

        if (streamData.streams.isEmpty()) return false

        streamData.streams.forEach { stream ->
            try {
                // Fetch the master playlist using custom OkHttp client (supporting DoH)
                val playlistUrl = "https://chatgpt.hereisman.net/playlist/${stream.url}/load-playlist"
                val refererUrl = "https://gooz.aapmains.net/new-stream-embed/${stream.url}?ad=111"
                
                val responseText = customGet(playlistUrl, referer = refererUrl)
                
                // Parse the sub-stream playlist URL from the master playlist response
                val subStreamMatch = """(https?://[^\s]+)""".toRegex().find(responseText)
                val finalUrl = subStreamMatch?.groupValues?.get(1) ?: playlistUrl

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        stream.name,
                        finalUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://gooz.aapmains.net/"
                    }
                )
            } catch (e: Exception) {
                println("StreamEast: Failed to resolve link for ${stream.name} - ${e.message}")
            }
        }

        return true
    }
}
