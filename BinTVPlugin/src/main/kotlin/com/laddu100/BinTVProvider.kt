package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class BinTVProvider : MainAPI() {

    override var mainUrl = "https://www.bintv.net"
    override var name = "BinTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // Data classes for internal state passing
    data class MatchSource(
        val name: String,
        val url: String,
        val type: String = "direct"
    )

    data class EventLoadData(
        val title: String,
        val poster: String?,
        val date: Long?,
        val endsAt: Long?,
        val category: String,
        val sources: List<MatchSource>,
        val isPPV: Boolean,
        val isBinTV: Boolean,
        val status: String? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String
    )

    // Data classes for parsing PPV API
    data class PpvStreamGroup(
        @JsonProperty("category") val category: String?,
        @JsonProperty("streams") val streams: List<PpvStream>?
    )

    data class PpvStream(
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String?,
        @JsonProperty("tag") val tag: String?,
        @JsonProperty("source_tag") val source_tag: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("starts_at") val starts_at: Long?,
        @JsonProperty("ends_at") val ends_at: Long?,
        @JsonProperty("always_live") val always_live: Int?,
        @JsonProperty("locale") val locale: String?,
        @JsonProperty("category_name") val category_name: String?,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("viewers") val viewers: String?,
        @JsonProperty("substreams") val substreams: List<PpvSubstream>?
    )

    data class PpvSubstream(
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String?,
        @JsonProperty("tag") val tag: String?,
        @JsonProperty("uri_name") val uri_name: String?,
        @JsonProperty("source_tag") val source_tag: String?,
        @JsonProperty("locale") val locale: String?,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("quality") val quality: String?
    )

    data class PpvStreamsResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("streams") val streams: List<PpvStreamGroup>?
    )

    // Data classes for parsing streamed JSON extras
    data class ExtraMatch(
        @JsonProperty("title") val title: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("url") val url: List<ExtraSource>?
    )

    data class ExtraSource(
        @JsonProperty("source") val source: String,
        @JsonProperty("url") val url: String
    )

    data class ExtraConfig(
        @JsonProperty("matches") val matches: List<ExtraMatch>?
    )

    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""[^\w\-]"""), "")
            .replace(Regex("""\-\-+"""), "-")
            .trim('-')
    }

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

    private suspend fun loadUrlViaWebView(url: String): String? {
        val ctx = context ?: return null
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                try {
                    val webView = android.webkit.WebView(ctx)
                    val settings = webView.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                    var resumed = false

                    webView.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            if (resumed) return
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!resumed) {
                                    webView.evaluateJavascript("(function() { return document.body ? document.body.innerText : document.documentElement.outerHTML; })()") { result ->
                                        if (!resumed) {
                                            resumed = true
                                            val cleanedResult = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                                                try {
                                                    com.fasterxml.jackson.databind.ObjectMapper().readValue(result, String::class.java)
                                                } catch (e: Exception) {
                                                    result
                                                }
                                            } else {
                                                result
                                            }
                                            
                                            try {
                                                webView.destroy()
                                            } catch (e: Exception) {}
                                            continuation.resume(cleanedResult)
                                        }
                                    }
                                }
                            }, 500)
                        }

                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (resumed) return
                            resumed = true
                            try {
                                webView.destroy()
                            } catch (e: Exception) {}
                            continuation.resume(null)
                        }
                    }

                    webView.loadUrl(url)

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!resumed) {
                            resumed = true
                            try {
                                webView.destroy()
                            } catch (e: Exception) {}
                            continuation.resume(null)
                        }
                    }, 8000)
                } catch (e: Exception) {
                    println("BinTV: loadUrlViaWebView exception: ${e.message}")
                    try {
                        continuation.resume(null)
                    } catch (ex: Exception) {}
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        // Fetch extras config
        val extrasMap = mutableMapOf<String, ExtraMatch>()
        try {
            val extraText = app.get("https://prabashsapkota.github.io/Streamed-images-json/index.json", timeout = 15L).text
            val extraConfig = parseJson<ExtraConfig>(extraText)
            extraConfig.matches?.forEach { m ->
                val title = m.title?.trim()?.lowercase()
                if (!title.isNullOrBlank()) {
                    extrasMap[title] = m
                }
            }
        } catch (e: Exception) {
            println("BinTV: failed to load extras - ${e.message}")
        }

        // Fetch BinTV matches
        val bintvMatches = mutableListOf<EventLoadData>()
        try {
            val bintvText = app.get("https://prabashsapkota.github.io/bintvjson/index.json", timeout = 15L).text
            val bintvList = parseJson<List<Map<String, Any>>>(bintvText)
            bintvList.forEach { item ->
                val name = item["name"] as? String ?: ""
                if (name.isBlank()) return@forEach
                val logo = item["logo"] as? String
                val category = item["category"] as? String ?: "Other"
                val time = item["time"] as? String ?: ""
                val isLive = time.lowercase() == "live"
                val dateMs = if (isLive) System.currentTimeMillis() else (System.currentTimeMillis() + 86400000)

                val sources = mutableListOf<MatchSource>()
                val urlVal = item["url"] as? String
                if (!urlVal.isNullOrBlank()) {
                    sources.add(MatchSource("Stream 1", urlVal))
                }
                item.forEach { (key, value) ->
                    if (key.startsWith("url_") && value is String && value.isNotBlank()) {
                        val srcName = key.substring(4).replace("_", " ")
                        sources.add(MatchSource(srcName, value))
                    }
                }

                bintvMatches.add(
                    EventLoadData(
                        title = name,
                        poster = logo,
                        date = dateMs,
                        endsAt = null,
                        category = category,
                        sources = sources,
                        isPPV = false,
                        isBinTV = true,
                        status = time
                    )
                )
            }
        } catch (e: Exception) {
            println("BinTV: failed to load bintvjson - ${e.message}")
        }

        // Apply extras
        bintvMatches.forEachIndexed { index, m ->
            val key = m.title.trim().lowercase()
            val extra = extrasMap[key]
            if (extra != null) {
                val updatedPoster = extra.poster ?: m.poster
                val updatedSources = m.sources.toMutableList()
                extra.url?.forEach { es ->
                    updatedSources.add(0, MatchSource(es.source, es.url))
                }
                bintvMatches[index] = m.copy(poster = updatedPoster, sources = updatedSources)
            }
        }

        // Fetch PPV matches
        val ppvMatches = mutableListOf<EventLoadData>()
        val ppvHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://www.bintv.net/",
            "Origin" to "https://www.bintv.net"
        )

        println("BinTV: Fetching PPV matches via loadUrlViaWebView...")
        val ppvText = loadUrlViaWebView("https://api.ppv.to/api/streams") ?: ""
        println("BinTV: loadUrlViaWebView response length: ${ppvText.length}")
        if (ppvText.length > 500) {
            println("BinTV: loadUrlViaWebView response snippet: ${ppvText.substring(0, 500)}")
        } else {
            println("BinTV: loadUrlViaWebView response snippet: $ppvText")
        }

        if (ppvText.isNotBlank()) {
            try {
                val ppvResponse = parseJson<PpvStreamsResponse>(ppvText)
                if (ppvResponse.success && !ppvResponse.streams.isNullOrEmpty()) {
                    val now = System.currentTimeMillis()
                    ppvResponse.streams.forEach { group ->
                        if (group.category == "24/7 Streams") return@forEach
                        group.streams?.forEach { stream ->
                            val name = stream.name ?: ""
                            if (name.isBlank()) return@forEach
                            val startMs = (stream.starts_at ?: 0) * 1000L
                            val endMs = (stream.ends_at ?: 0) * 1000L
                            // Filter ended matches
                            if (stream.ends_at != null && endMs < now - 600000L) return@forEach

                            val iframes = mutableListOf<MatchSource>()
                            if (!stream.iframe.isNullOrBlank()) {
                                // Get stream name
                                val mainName = stream.source_tag?.takeIf { it.isNotBlank() } ?: "Main"
                                iframes.add(MatchSource(mainName, stream.iframe))
                            }
                            stream.substreams?.forEachIndexed { subIdx, sub ->
                                if (!sub.iframe.isNullOrBlank() && iframes.none { it.url == sub.iframe }) {
                                    // Get fallback name
                                    val rawName = sub.uri_name?.takeIf { it.isNotBlank() }
                                        ?: sub.source_tag?.takeIf { it.isNotBlank() }
                                        ?: sub.name?.takeIf { it.isNotBlank() }
                                        ?: "Server ${subIdx + 1}"
                                    // Normalize name
                                    val prettyName = rawName
                                        .split("-").joinToString(" ") { part ->
                                            if (part.length <= 3) part.uppercase()
                                            else part.replaceFirstChar { it.uppercase() }
                                        }
                                    iframes.add(MatchSource(prettyName, sub.iframe))
                                }
                            }

                            if (iframes.isEmpty()) return@forEach

                            val numberedSources = iframes.mapIndexed { idx, src ->
                                MatchSource(
                                    name = "${src.name} [${idx + 1}]",
                                    url = src.url
                                )
                            }

                            ppvMatches.add(
                                EventLoadData(
                                    title = name,
                                    poster = stream.poster,
                                    date = startMs,
                                    endsAt = endMs,
                                    category = stream.category_name ?: group.category ?: "Other",
                                    sources = numberedSources,
                                    isPPV = true,
                                    isBinTV = false
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                println("BinTV: failed to parse PPV JSON - ${e.message}")
            }
        }

        // Apply extras
        ppvMatches.forEachIndexed { index, m ->
            val key = m.title.trim().lowercase()
            val extra = extrasMap[key]
            if (extra != null) {
                val updatedPoster = extra.poster ?: m.poster
                val updatedSources = m.sources.toMutableList()
                extra.url?.forEach { es ->
                    updatedSources.add(0, MatchSource(es.source, es.url))
                }
                ppvMatches[index] = m.copy(poster = updatedPoster, sources = updatedSources)
            }
        }

        // Deduplicate and merge
        val existingTitles = bintvMatches.map { slugify(it.title) }.toSet()
        val filteredPpv = ppvMatches.filter { !existingTitles.contains(slugify(it.title)) }
        val allMatches = bintvMatches + filteredPpv

        // Split live/upcoming
        val now = System.currentTimeMillis()
        val liveList = mutableListOf<EventLoadData>()
        val upcomingList = mutableListOf<EventLoadData>()

        allMatches.forEach { m ->
            val isLive = if (m.isBinTV) {
                m.status?.lowercase() == "live"
            } else {
                val start = m.date ?: 0L
                val end = m.endsAt ?: Long.MAX_VALUE
                (now >= start - 3600000L && now <= end)
            }
            if (isLive) {
                liveList.add(m)
            } else {
                upcomingList.add(m)
            }
        }

        // Map matches
        fun matchToSearch(m: EventLoadData, prefix: String = ""): SearchResponse {
            val title = if (prefix.isNotEmpty()) "$prefix ${m.title}" else m.title
            return newLiveSearchResponse(title, m.toJson(), TvType.Live) {
                this.posterUrl = m.poster
            }
        }

        // FIFA World Cup
        val wcLive = liveList.filter { m ->
            m.title.contains("world cup", ignoreCase = true) ||
            m.title.contains("fifa", ignoreCase = true) ||
            m.category.contains("world cup", ignoreCase = true)
        }
        if (wcLive.isNotEmpty()) {
            val items = wcLive.map { matchToSearch(it) }
            lists.add(HomePageList("🏆 FIFA World Cup - Live", items, isHorizontalImages = true))
        }

        // Live Sports
        val sports = listOf(
            Pair("Football", "🟢 Live Football"),
            Pair("Basketball", "🟢 Live Basketball"),
            Pair("Cricket", "🟢 Live Cricket"),
            Pair("Tennis", "🟢 Live Tennis"),
            Pair("Fight", "🟢 Live Fight / Combat")
        )

        sports.forEach { (catKey, sectionName) ->
            val sportMatches = liveList.filter { m ->
                !wcLive.contains(m) && (
                    m.category.contains(catKey, ignoreCase = true) ||
                    (catKey == "Fight" && (
                        m.category.contains("wrestling", ignoreCase = true) ||
                        m.category.contains("combat", ignoreCase = true) ||
                        m.category.contains("boxing", ignoreCase = true)
                    ))
                )
            }
            if (sportMatches.isNotEmpty()) {
                val items = sportMatches.map { matchToSearch(it) }
                lists.add(HomePageList(sectionName, items, isHorizontalImages = true))
            }
        }

        // Other Live
        val otherLive = liveList.filter { m ->
            !wcLive.contains(m) && sports.none { (catKey, _) ->
                m.category.contains(catKey, ignoreCase = true) ||
                (catKey == "Fight" && (
                    m.category.contains("wrestling", ignoreCase = true) ||
                    m.category.contains("combat", ignoreCase = true) ||
                    m.category.contains("boxing", ignoreCase = true)
                ))
            }
        }
        if (otherLive.isNotEmpty()) {
            val items = otherLive.map { matchToSearch(it) }
            lists.add(HomePageList("🟢 Live Other Sports", items, isHorizontalImages = true))
        }

        // Upcoming FIFA
        val wcUpcoming = upcomingList.filter { m ->
            m.title.contains("world cup", ignoreCase = true) ||
            m.title.contains("fifa", ignoreCase = true) ||
            m.category.contains("world cup", ignoreCase = true)
        }
        if (wcUpcoming.isNotEmpty()) {
            val items = wcUpcoming.map { matchToSearch(it, "Upcoming:") }
            lists.add(HomePageList("🏆 FIFA World Cup - Upcoming", items, isHorizontalImages = true))
        }

        // Upcoming schedule
        val otherUpcoming = upcomingList.filter { !wcUpcoming.contains(it) }
            .sortedBy { it.date ?: 0L }
        if (otherUpcoming.isNotEmpty()) {
            val items = otherUpcoming.map { m ->
                val dateStr = formatMatchDate(m.date)
                val displayTitle = "${m.title} [Starts: $dateStr]"
                newLiveSearchResponse(displayTitle, m.toJson(), TvType.Live) {
                    this.posterUrl = m.poster
                }
            }
            lists.add(HomePageList("📅 Upcoming Sports Schedule", items, isHorizontalImages = true))
        }

        // Empty state handling
        if (liveList.isEmpty()) {
            val dummyData = EventLoadData(
                title = "No live matches right now",
                poster = "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEi9H8iKbJ5ngROc8c8npv__rsAbMJDRjNNJFL3LrnstW0SWeQn9sQdb-_6wyTQPAL9P_B9n_DRjs3G1srZJTaaBH9LTqG2B1LWdvKkD-E8BRVjaY408MmWJPcinCS4cxFrOPMRlgoREqEs8sNCnQfpXEr0RmxjjPMn0GvWJXdJF1zov3pa7FgCwDOJ6_Q/s1853/bintv.png",
                date = null,
                endsAt = null,
                category = "None",
                sources = emptyList(),
                isPPV = false,
                isBinTV = false
            )
            val dummyItem = newLiveSearchResponse(
                "No live matches right now. Please check back later!",
                dummyData.toJson(),
                TvType.Live
            ) {
                this.posterUrl = dummyData.poster
            }
            lists.add(0, HomePageList("🟢 Live Sports", listOf(dummyItem), isHorizontalImages = true))
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val eventData = parseJson<EventLoadData>(url)
        val title = eventData.title
        val poster = eventData.poster

        if (title == "No live matches right now") {
            return newLiveStreamLoadResponse(title, url, this.name) {
                this.posterUrl = poster
                this.plot = "There are no live matches broadcasting at the moment. Please check the upcoming schedule list."
                this.dataUrl = url
            }
        }

        val streams = eventData.sources.map { src ->
            StreamInfo(name = src.name, url = src.url)
        }

        val streamData = StreamLoadData(title, streams)

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = poster
            this.dataUrl = streamData.toJson()
        }
    }

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
                // Extract source from noooooads wrapper
                var embedUrl = stream.url
                if (embedUrl.contains("noooooads/?src=")) {
                    val extracted = embedUrl.substringAfter("noooooads/?src=").substringBefore("&")
                    if (extracted.isNotBlank()) {
                        embedUrl = java.net.URLDecoder.decode(extracted, "UTF-8")
                    }
                }

                // Direct m3u8 HLS
                if (embedUrl.contains(".m3u8")) {
                    val referer = getBaseUrl(embedUrl)
                    val proxiedUrl = getProxiedM3u8Url(embedUrl, referer)
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = stream.name,
                            url = proxiedUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                                "Referer" to referer
                            )
                        }
                    )
                    foundAny = true
                } else {
                    // Parse embed HTML
                    val embedHost = try {
                        val uri = java.net.URI(embedUrl)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        null
                    }

                    val isEmbedIndia = embedUrl.contains("embedindia.st", ignoreCase = true)
                    if (isEmbedIndia) {
                        try {
                            val ctx = context
                            if (ctx != null) {
                                val extractor = com.laddu100.EmbedIndiaExtractor(ctx)
                                extractor.getUrl(
                                    url = embedUrl,
                                    referer = "${embedHost ?: "https://bintv.net"}/",
                                    subtitleCallback = subtitleCallback,
                                    callback = callback
                                )
                                foundAny = true
                            } else {
                                println("BinTV: EmbedIndiaExtractor failed - static context is null")
                            }
                        } catch (e: Exception) {
                            println("BinTV: EmbedIndiaExtractor failed for $embedUrl - ${e.message}")
                        }
                    } else {
                        val fetchHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                            "Referer" to "${embedHost ?: "https://bintv.net"}/",
                            "Origin" to (embedHost ?: "https://bintv.net")
                        )

                        var embedHtml = ""
                        try {
                            val res = app.get(embedUrl, headers = fetchHeaders, timeout = 20L)
                            embedHtml = res.text
                            if (embedHtml.contains("Just a moment") || res.code == 403) {
                                embedHtml = app.get(embedUrl, headers = fetchHeaders, interceptor = cfInterceptor, timeout = 20L).text
                            }
                        } catch (fetchErr: Exception) {
                            println("BinTV: Failed to fetch embed page $embedUrl - ${fetchErr.message}")
                            try {
                                embedHtml = app.get(embedUrl, headers = fetchHeaders, interceptor = cfInterceptor, timeout = 20L).text
                            } catch (cfErr: Exception) {
                                println("BinTV: Failed to fetch embed page with CloudflareKiller - ${cfErr.message}")
                            }
                        }

                        // Search for m3u8 links
                        val m3u8Pattern = Regex("""(https?://[^\s"'\\]+\.m3u8(?:[^\s"'\\]*)?)""")
                        val m3u8Matches = m3u8Pattern.findAll(embedHtml)
                        var foundM3u8 = false
                        m3u8Matches.forEachIndexed { idx, match ->
                            val m3u8Url = match.value
                                .replace("\\u0026", "&")
                                .replace("\\/", "/")
                            val m3u8Host = try {
                                val uri = java.net.URI(m3u8Url)
                                "${uri.scheme}://${uri.host}"
                            } catch (e: Exception) {
                                embedHost ?: getBaseUrl(m3u8Url)
                            }
                            val proxiedUrl = getProxiedM3u8Url(m3u8Url, embedHost ?: m3u8Host)
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = if (idx == 0) stream.name else "${stream.name} (Alt ${idx + 1})",
                                    url = proxiedUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                                        "Referer" to "$m3u8Host/",
                                        "Origin" to m3u8Host
                                    )
                                }
                            )
                            foundM3u8 = true
                            foundAny = true
                        }

                        // Fallback to loadExtractor
                        if (!foundM3u8) {
                            try {
                                loadExtractor(embedUrl, "${embedHost ?: "https://bintv.net"}/", subtitleCallback, callback)
                                foundAny = true
                            } catch (e: Exception) {
                                println("BinTV: fallback loadExtractor failed for $embedUrl - ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("BinTV: Failed to load stream link - ${e.message}")
            }
        }

        return foundAny
    }

    companion object {
        var context: android.content.Context? = null
        private val cfInterceptor = CloudflareKiller()
        private var serverSocket: ServerSocket? = null
        private var port: Int = 0

        @Synchronized
        fun startProxy() {
            if (serverSocket != null && !serverSocket!!.isClosed) return
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                thread(start = true, isDaemon = true) {
                    try {
                        while (true) {
                            val socket = serverSocket?.accept() ?: break
                            thread {
                                handleSocket(socket)
                            }
                        }
                    } catch (e: Exception) {
                        println("BinTV: Proxy accept loop error - ${e.message}")
                    } finally {
                        try {
                            serverSocket?.close()
                        } catch (e: Exception) {}
                        serverSocket = null
                    }
                }
                println("BinTV: LocalProxy started on port $port")
            } catch (e: Exception) {
                println("BinTV: LocalProxy start failed: ${e.message}")
            }
        }

        fun getProxiedM3u8Url(m3u8Url: String, referer: String, userAgent: String? = null): String {
            startProxy()
            val formattedReferer = if (referer.endsWith("/")) referer else "$referer/"
            val encodedUrl = java.net.URLEncoder.encode(m3u8Url, "UTF-8")
            val encodedReferer = java.net.URLEncoder.encode(formattedReferer, "UTF-8")
            val encodedUa = userAgent?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            val uaParam = if (encodedUa != null) "&ua=$encodedUa" else ""
            return "http://127.0.0.1:$port/playlist.m3u8?referer=$encodedReferer&url=$encodedUrl$uaParam"
        }

        private fun getBaseUrl(url: String): String {
            return try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                ""
            }
        }

        private fun getQueryParam(path: String, name: String): String? {
            val query = path.substringAfter("?", "")
            if (query.isEmpty()) return null
            return query.split("&").firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.let {
                try {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } catch (e: Exception) {
                    null
                }
            }
        }

        private fun handleSocket(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                val firstLine = reader.readLine() ?: return
                // Consume headers
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }

                val parts = firstLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                if (path.startsWith("/playlist.m3u8")) {
                    val targetUrl = getQueryParam(path, "url") ?: return
                    val referer = getQueryParam(path, "referer") ?: getBaseUrl(targetUrl)
                    val userAgent = getQueryParam(path, "ua") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

                    val response = runBlocking {
                        app.get(
                            targetUrl,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to referer
                            ),
                            timeout = 30L
                        )
                    }
                    val playlistText = response.text
                    val baseUrl = targetUrl.substringBeforeLast("/")

                    var isStreamInf = false
                    val rewritten = playlistText.lineSequence().map { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || (trimmed.startsWith("#") && !trimmed.contains("URI=\""))) {
                            isStreamInf = trimmed.startsWith("#EXT-X-STREAM-INF")
                            line
                        } else if (trimmed.startsWith("#") && trimmed.contains("URI=\"")) {
                            val preUri = line.substringBefore("URI=\"")
                            val uriVal = line.substringAfter("URI=\"").substringBefore("\"")
                            val postUri = line.substringAfter("URI=\"").substringAfter("\"")

                            val absoluteUrl = if (uriVal.startsWith("http")) {
                                uriVal
                            } else if (uriVal.startsWith("/")) {
                                val host = getBaseUrl(targetUrl)
                                "$host$uriVal"
                            } else {
                                "$baseUrl/$uriVal"
                            }
                            val encodedUrl = java.net.URLEncoder.encode(absoluteUrl, "UTF-8")
                            val encodedReferer = java.net.URLEncoder.encode(referer, "UTF-8")
                            val encodedUa = java.net.URLEncoder.encode(userAgent, "UTF-8")
                            val uaParam = "&ua=$encodedUa"

                            val proxiedUri = if (absoluteUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)) {
                                "http://127.0.0.1:$port/playlist.m3u8?referer=$encodedReferer&url=$encodedUrl$uaParam"
                            } else {
                                "http://127.0.0.1:$port/proxy?referer=$encodedReferer&url=$encodedUrl$uaParam"
                            }
                            "${preUri}URI=\"$proxiedUri\"$postUri"
                        } else {
                            val absoluteUrl = if (trimmed.startsWith("http")) {
                                trimmed
                            } else if (trimmed.startsWith("/")) {
                                val host = getBaseUrl(targetUrl)
                                "$host$trimmed"
                            } else {
                                "$baseUrl/$trimmed"
                            }
                            val encodedUrl = java.net.URLEncoder.encode(absoluteUrl, "UTF-8")
                            val encodedReferer = java.net.URLEncoder.encode(referer, "UTF-8")
                            val encodedUa = java.net.URLEncoder.encode(userAgent, "UTF-8")
                            val uaParam = "&ua=$encodedUa"

                            val result = if (isStreamInf || absoluteUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)) {
                                "http://127.0.0.1:$port/playlist.m3u8?referer=$encodedReferer&url=$encodedUrl$uaParam"
                            } else {
                                "http://127.0.0.1:$port/proxy?referer=$encodedReferer&url=$encodedUrl$uaParam"
                            }
                            isStreamInf = false
                            result
                        }
                    }.joinToString("\n")

                    val out = socket.getOutputStream()
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
                    out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                    out.write("Connection: close\r\n".toByteArray())
                    out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                    out.write("\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                } else if (path.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(path, "url") ?: return
                    val referer = getQueryParam(path, "referer") ?: getBaseUrl(targetUrl)
                    val userAgent = getQueryParam(path, "ua") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

                    val response = runBlocking {
                        app.get(
                            targetUrl,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to referer
                            ),
                            timeout = 30L
                        )
                    }
                    val bytes = response.body.bytes()
                    val hasPngHeader = bytes.size > 70 &&
                            bytes[0] == 0x89.toByte() &&
                            bytes[1] == 0x50.toByte() &&
                            bytes[2] == 0x4E.toByte() &&
                            bytes[3] == 0x47.toByte()
                    val cleanBytes = if (hasPngHeader) {
                        bytes.copyOfRange(70, bytes.size)
                    } else {
                        bytes
                    }

                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: video/mp2t\r\n".toByteArray())
                    out.write("Content-Length: ${cleanBytes.size}\r\n".toByteArray())
                    out.write("Connection: close\r\n".toByteArray())
                    out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                    out.write("\r\n".toByteArray())
                    out.write(cleanBytes)
                    out.flush()
                }
            } catch (e: Exception) {
                println("BinTV: Proxy error - ${e.message}")
            } finally {
                try {
                    socket.shutdownOutput()
                } catch (e: Exception) {}
                try {
                    socket.close()
                } catch (e: Exception) {}
            }
        }
    }
}
