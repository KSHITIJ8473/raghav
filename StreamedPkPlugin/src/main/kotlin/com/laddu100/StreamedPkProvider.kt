package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ConsoleMessage
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import android.util.Log

class StreamedPkProvider : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed.pk"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
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
            .readTimeout(20, TimeUnit.SECONDS)
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

    // ── DDoS-Guard cookie management ───────────────────────────────────────────
    // streamed.pk is fronted by DDoS-Guard (server: ddos-guard) which intermittently
    // issues challenge cookies (__ddg1_, __ddg8_, __ddg9_, __ddg10_ and, under
    // stricter scrutiny, a JS-challenge cookie __ddg2_). OkHttp cannot execute
    // the JS challenge, but DDoS-Guard lets a large share of requests through
    // once the tracking cookies are present and echoed back. The previous code
    // never persisted or re-sent these cookies, so every home-page load was a
    // cold coin-flip against DDoS-Guard -> "no live matches" until the user
    // reloaded enough times for one request to slip through and seed the
    // (baseClient) cookie jar. We now manage cookies explicitly here so the
    // success rate is high and predictable regardless of the base client config.
    private val cookieStore = ConcurrentHashMap<String, String>()

    private fun parseAndStoreCookies(headers: Headers) {
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            if (!name.equals("Set-Cookie", ignoreCase = true)) continue
            val raw = headers.value(i) ?: continue
            val cookiePart = raw.substringBefore(";").trim()
            val eqIdx = cookiePart.indexOf('=')
            if (eqIdx <= 0) continue
            val cName = cookiePart.substring(0, eqIdx).trim()
            val cValue = cookiePart.substring(eqIdx + 1).trim()
            if (cName.isNotEmpty() && cValue.isNotEmpty()) {
                cookieStore[cName] = cValue
            }
        }
    }

    private fun cookieHeader(): String =
        if (cookieStore.isEmpty()) "" else cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * Warm up DDoS-Guard cookies by fetching the main HTML page.
     * The HTML page is served leniently (always 200 with __ddg* cookies) and
     * seeds the cookie store so the subsequent /api/matches/all call is far
     * more likely to pass DDoS-Guard without a challenge.
     */
    private fun warmUpCookies() {
        try {
            val builder = Request.Builder()
                .url("$mainUrl/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
            val c = cookieHeader()
            if (c.isNotEmpty()) builder.header("Cookie", c)
            dnsClient.newCall(builder.build()).execute().use { response ->
                parseAndStoreCookies(response.headers)
            }
        } catch (e: Exception) {
            println("StreamedPk: warm-up error - ${e.message}")
        }
    }

    /**
     * Single-attempt GET with cookie send/store + DDoS-Guard challenge detection.
     * Throws on HTTP error or when an HTML challenge page is returned instead of data.
     */
    private fun customGetRaw(path: String, acceptHtml: Boolean = false): String {
        val url = if (path.startsWith("http")) path else "$mainUrl$path"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .header("Accept", if (acceptHtml) "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" else "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$mainUrl/")
            .header("Origin", mainUrl)
        val c = cookieHeader()
        if (c.isNotEmpty()) builder.header("Cookie", c)

        dnsClient.newCall(builder.build()).execute().use { response ->
            // Always persist any cookies DDoS-Guard hands back, even on error responses.
            parseAndStoreCookies(response.headers)

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            // Detect a DDoS-Guard challenge: the API must return JSON. If we get
            // HTML (challenge / interstitial page) instead, surface a retryable error.
            if (!acceptHtml) {
                val trimmed = body.trimStart()
                if (trimmed.startsWith("<") || body.contains("__ddg", ignoreCase = true)) {
                    throw Exception("DDoS-Guard challenge page (HTML) instead of data")
                }
            }
            return body
        }
    }

    /**
     * Retrying GET with exponential backoff + periodic cookie warm-up.
     *
     * streamed.pk's DDoS-Guard intermittently challenges requests; a single
     * transient failure must NOT surface to the user as "no matches". We retry
     * up to [maxAttempts] times, priming cookies before the first attempt and
     * again after a couple of failures so later attempts carry fresh __ddg* cookies.
     */
    private suspend fun customGetWithRetry(
        path: String,
        maxAttempts: Int = 6,
        acceptHtml: Boolean = false
    ): String {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt == 1 || attempt == 3 || attempt == 5) {
                    warmUpCookies()
                }
                return customGetRaw(path, acceptHtml)
            } catch (e: Exception) {
                lastError = e
                println("StreamedPk: GET $path attempt $attempt/$maxAttempts failed - ${e.message}")
                if (attempt < maxAttempts) {
                    val backoff = (attempt * 450L).coerceAtMost(2500L)
                    try { delay(backoff) } catch (_: Exception) {}
                }
            }
        }
        throw lastError ?: Exception("GET $path failed after $maxAttempts attempts")
    }

    // ── In-memory matches cache ────────────────────────────────────────────────
    // Serves recent data on transient failures so the home page never shows
    // "no matches" when matches actually exist.
    private data class CachedMatches(val matches: List<StreamedMatch>, val timestamp: Long)
    @Volatile private var matchesCache: CachedMatches? = null
    private val CACHE_TTL_MS = 60_000L          // considered fresh within 60s
    private val CACHE_STALE_LIMIT_MS = 600_000L // serve stale up to 10min on total failure

    private suspend fun fetchAllMatches(): List<StreamedMatch> {
        try {
            val text = customGetWithRetry("/api/matches/all")
            val matches = parseJson<List<StreamedMatch>>(text)
            matchesCache = CachedMatches(matches, System.currentTimeMillis())
            return matches
        } catch (e: Exception) {
            // Last-resort: serve cached data (even stale) instead of failing,
            // so the user never sees an empty home page due to a transient blip.
            val cached = matchesCache
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_STALE_LIMIT_MS) {
                println("StreamedPk: API failed (${e.message}); serving cached matches (age=${(System.currentTimeMillis() - cached.timestamp) / 1000}s)")
                return cached.matches
            }
            throw e
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
        val lists = mutableListOf<HomePageList>()
        var fetchFailed = false

        try {
            val allMatches = fetchAllMatches()

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
            println("StreamedPk: getMainPage failed after retries - ${e.message}")
            fetchFailed = true
        }

        // Only show the placeholder when there is genuinely nothing to display.
        // A transient fetch failure shows a distinct "connection issue" message so
        // the user knows it is not an empty catalogue and should pull to refresh.
        if (lists.isEmpty()) {
            val dummyLoadData = EventLoadData(
                title = if (fetchFailed) "Connection issue" else "No matches available",
                id = "dummy",
                posterUrl = "",
                date = null,
                category = "other",
                sources = null
            )
            val message = if (fetchFailed) {
                "Connection issue with streamed.pk. Pull down to refresh — live matches are being loaded."
            } else {
                "No matches available right now. Please check back later!"
            }
            val dummyItem = newLiveSearchResponse(
                name = message,
                url = dummyLoadData.toJson(),
                type = TvType.Live
            )
            lists.add(HomePageList("Matches Status", listOf(dummyItem), isHorizontalImages = true))
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val allMatches = fetchAllMatches()
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
            val allMatches = fetchAllMatches()
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
                    val streamText = customGetWithRetry(streamUrl)
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

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun getPlayHeaders(embedUrl: String): Map<String, String> {
        val embedHost = try {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://embed.st"
        }
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Referer" to "$embedHost/",
            "Origin" to embedHost,
            "Accept" to "*/*"
        )
    }

    private suspend fun resolveStreamUrl(url: String, referer: String?): String? {
        val ctx = context ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val captured = AtomicBoolean(false)
                var webView: WebView? = null

                val cleanUp = {
                    if (captured.compareAndSet(false, true)) {
                        try {
                            webView?.destroy()
                        } catch (e: Exception) {}
                        continuation.resume(null)
                    }
                }

                try {
                    webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = ua

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                val msg = consoleMessage?.message() ?: ""
                                Log.d("StreamedPkJS", "[Console] $msg")
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                Log.d("StreamedPk", "Page finished loading: $pageUrl")

                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (captured.get()) return@postDelayed
                                    view?.evaluateJavascript(playScript) { result ->
                                        Log.d("StreamedPkJS", "Hook injection result: $result")
                                    }
                                }, 1500)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                Log.d("StreamedPkNet", "Request: $reqUrl")

                                if ((reqUrl.contains(".m3u8", ignoreCase = true) || reqUrl.contains("master.txt", ignoreCase = true)) && !captured.get()) {
                                    Log.d("StreamedPk", "Captured stream URL: $reqUrl")
                                    if (captured.compareAndSet(false, true)) {
                                        Handler(Looper.getMainLooper()).post {
                                            try {
                                                webView?.destroy()
                                            } catch (e: Exception) {}
                                        }
                                        continuation.resume(reqUrl)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    val headers = HashMap<String, String>()
                    if (referer != null) {
                        headers["Referer"] = referer
                    }
                    val embedHost = try {
                        val uri = java.net.URI(url)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        "https://embed.st"
                    }
                    headers["Origin"] = embedHost

                    Log.d("StreamedPk", "Loading URL in WebView: $url")
                    webView.loadUrl(url, headers)

                    // Timeout after 30 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            Log.d("StreamedPk", "Timeout waiting for stream link")
                            try {
                                webView.destroy()
                            } catch (e: Exception) {}
                            continuation.resume(null)
                        }
                    }, 30000)

                } catch (e: Exception) {
                    Log.e("StreamedPk", "Error initializing WebView: ${e.message}")
                    cleanUp()
                }
            }
        }
    }

    private val playScript = """
        (function() {
            if (window.__interceptor_installed) return "already_installed";
            Object.defineProperty(window, '__interceptor_installed', {
                value: true,
                writable: true,
                configurable: true,
                enumerable: false
            });

            function log(msg) {
                console.log("[Hook] " + msg);
            }

            log("Installing stealth hooks...");

            function triggerInterception(url) {
                if (!url) return;
                var urlStr = (url && typeof url.toString === 'function') ? url.toString() : url;
                log("Triggering interception for URL: " + urlStr);
                if (urlStr.indexOf('m3u8') !== -1 || urlStr.indexOf('master.txt') !== -1) {
                    window.location.href = urlStr;
                }
            }

            // 1. Hook HTMLMediaElement.prototype.src
            try {
                var originalSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (originalSrcDescriptor && originalSrcDescriptor.set) {
                    var originalSet = originalSrcDescriptor.set;
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        set: function(val) {
                            log("MediaElement src set: " + val);
                            triggerInterception(val);
                            return originalSet.apply(this, arguments);
                        },
                        configurable: true,
                        enumerable: true
                    });
                    log("MediaElement.src hooked.");
                }
            } catch(e) {
                log("Error hooking MediaElement.src: " + e.message);
            }

            // 2. Hook HTMLSourceElement.prototype.src
            try {
                var originalSourceSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype, 'src');
                if (originalSourceSrcDescriptor && originalSourceSrcDescriptor.set) {
                    var originalSourceSet = originalSourceSrcDescriptor.set;
                    Object.defineProperty(HTMLSourceElement.prototype, 'src', {
                        set: function(val) {
                            log("SourceElement src set: " + val);
                            triggerInterception(val);
                            return originalSourceSet.apply(this, arguments);
                        },
                        configurable: true,
                        enumerable: true
                    });
                    log("SourceElement.src hooked.");
                }
            } catch(e) {
                log("Error hooking SourceElement.src: " + e.message);
            }
        })();
    """.trimIndent()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
                    val queryString = if (queryIndex != -1) stripped.substring(queryIndex + 1) else ""

                    var fallbackUrl = ""

                    if (queryString.isNotEmpty()) {
                        val params = queryString.split('&')
                        for (param in params) {
                            val pair = param.split('=', limit = 2)
                            if (pair.size == 2) {
                                when (pair[0]) {
                                    "fallback" -> fallbackUrl = java.net.URLDecoder.decode(pair[1], "UTF-8")
                                }
                            }
                        }
                    }

                    if (fallbackUrl.isNotEmpty()) {
                        Log.d("StreamedPk", "Resolving embed URL via WebView: $fallbackUrl")
                        try {
                            val resolvedUrl = resolveStreamUrl(fallbackUrl, "https://streamed.pk/")
                            if (resolvedUrl != null) {
                                Log.d("StreamedPk", "Successfully resolved URL: $resolvedUrl")
                                val embedHost = try {
                                    val uri = java.net.URI(fallbackUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) {
                                    "https://embed.st"
                                }
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = stream.name,
                                        url = resolvedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "$embedHost/"
                                        this.headers = getPlayHeaders(fallbackUrl)
                                    }
                                )
                                foundAny = true
                            } else {
                                Log.w("StreamedPk", "WebView resolver returned null for $fallbackUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedPk", "WebView resolution failed for $fallbackUrl: ${e.message}")
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
