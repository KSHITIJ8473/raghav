package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
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

    private data class CachedMatches(val matches: List<StreamedMatch>, val timestamp: Long)
    @Volatile private var matchesCache: CachedMatches? = null
    private val CACHE_TTL_MS = 60_000L
    private val CACHE_STALE_LIMIT_MS = 600_000L

    private suspend fun fetchAllMatches(): List<StreamedMatch> {
        try {
            val res = app.get("$mainUrl/api/matches/all", headers = apiHeaders, timeout = 30_000L)
            val matches = parseJson<List<StreamedMatch>>(res.text)
            matchesCache = CachedMatches(matches, System.currentTimeMillis())
            return matches
        } catch (e: Exception) {
            val cached = matchesCache
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_STALE_LIMIT_MS) {
                return cached.matches
            }
            throw e
        }
    }

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

    private fun getCategoryTitle(cat: String?): String {
        val clean = cat?.lowercase() ?: ""
        return when (clean) {
 "football" -> " Football (Upcoming)"
 "basketball" -> " Basketball (Upcoming)"
 "american-football" -> " American Football (Upcoming)"
 "hockey" -> " Hockey (Upcoming)"
 "baseball" -> " Baseball (Upcoming)"
 "motor-sports" -> "️ Motor Sports (Upcoming)"
 "fight" -> " Fight (UFC, Boxing) (Upcoming)"
 "tennis" -> " Tennis (Upcoming)"
 "rugby" -> " Rugby (Upcoming)"
 "golf" -> " Golf (Upcoming)"
 "billiards" -> " Billiards (Upcoming)"
 "afl" -> " AFL (Upcoming)"
 "darts" -> " Darts (Upcoming)"
 "cricket" -> " Cricket (Upcoming)"
 "other" -> " Other Sports (Upcoming)"
 else -> " ${clean.replaceFirstChar { it.uppercase() }} (Upcoming)"
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("streamedpk") ?: mainUrl
        val lists = mutableListOf<HomePageList>()
        var fetchFailed = false

        try {
            val allMatches = fetchAllMatches()

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
 lists.add(HomePageList(" Live Matches", liveItems, isHorizontalImages = true))
            }

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
 lists.add(HomePageList(" Upcoming Matches", upcomingItems, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            fetchFailed = true
        }

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

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("streamedpk") ?: mainUrl
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
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        mainUrl = FirebaseDomainHelper.getDomain("streamedpk") ?: mainUrl
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

        try {
            val allMatches = fetchAllMatches()
            val freshMatch = allMatches.find { it.id == eventData.id }
            if (freshMatch != null) {
                sources = freshMatch.sources
                dateVal = freshMatch.date
                posterUrl = getPosterForMatch(freshMatch.category, freshMatch.poster)
            }
        } catch (e: Exception) {
        }

        val isUpcoming = sources.isNullOrEmpty()
        val dateStr = formatMatchDate(dateVal)

        if (!sources.isNullOrEmpty()) {
            sources.forEach { src ->
                try {
                    val streamUrl = "$mainUrl/api/stream/${src.source}/${src.id}"
                    val streamText = app.get(streamUrl, headers = apiHeaders, timeout = 30_000L).text
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
                }
            }
        }

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
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)

                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (captured.get()) return@postDelayed
                                    view?.evaluateJavascript(playScript) { result ->
                                    }
                                }, 1500)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                if ((reqUrl.contains(".m3u8", ignoreCase = true) || reqUrl.contains("master.txt", ignoreCase = true)) && !captured.get()) {
                                    if (captured.compareAndSet(false, true)) {
                                        Handler(Looper.getMainLooper()).post {
                                            try {
                                                webView?.destroy()
                                            } catch (e: Exception) {}
                                        }
                                        continuation.resume(reqUrl)
                                    }
                                    return null
                                }

                                return null
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
            }
        }

        return foundAny
    }
}
