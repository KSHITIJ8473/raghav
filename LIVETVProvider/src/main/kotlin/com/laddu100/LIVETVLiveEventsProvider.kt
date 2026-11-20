package com.laddu100

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LIVETVLiveEventsProvider(
    override var name: String = "⚡ LIVE TV Live Events",
    private val mode: String = "live",
) : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://reliablewebcenter.online"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
    )

    data class LiveEventLoadData(
        val channelId: String,
        val title: String,
        val poster: String,
        val category: String?,
        val eventInfo: LIVETVEventInfo?,
        val formats: List<String>,
    )

    private fun createDisplayTitle(channel: LIVETVChannel): String {
        val eventInfo = channel.eventInfo
        if (eventInfo != null &&
            !eventInfo.teamA.isNullOrBlank() &&
            !eventInfo.teamB.isNullOrBlank()
        ) {
            return if (isSameTeam(eventInfo.teamA, eventInfo.teamB)) {
                eventInfo.teamA
            } else {
                "${eventInfo.teamA} vs ${eventInfo.teamB}"
            }
        }
        return channel.title ?: "Unknown"
    }

    private fun getEventStatus(channel: LIVETVChannel): String {
        val eventInfo = channel.eventInfo ?: return ""
        val now = System.currentTimeMillis()

        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startTime = eventInfo.startTime?.let { dateFormat.parse(it)?.time }
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }

            when {
                endTime != null && now >= endTime -> "✅"
                startTime != null && now < startTime -> "🔜"
                startTime != null && now >= startTime -> "🔴"
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isEventLive(channel: LIVETVChannel): Boolean {
        val eventInfo = channel.eventInfo ?: return false
        val now = System.currentTimeMillis()

        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startTime = eventInfo.startTime?.let { dateFormat.parse(it)?.time }
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }

            (endTime == null || now < endTime) && startTime != null && now >= startTime
        } catch (_: Exception) {
            false
        }
    }

    private fun isEventEnded(channel: LIVETVChannel): Boolean {
        val eventInfo = channel.eventInfo ?: return false
        val now = System.currentTimeMillis()

        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }
            endTime != null && now >= endTime
        } catch (_: Exception) {
            false
        }
    }

    private fun isSameTeam(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) {
            return false
        }
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    private fun generateMatchCardUrl(channel: LIVETVChannel): String {
        val eventInfo = channel.eventInfo
        val title = java.net.URLEncoder.encode(eventInfo?.eventName ?: channel.title ?: "", "UTF-8")
        val teamA = java.net.URLEncoder.encode(eventInfo?.teamA ?: "Team A", "UTF-8")
        val teamB = java.net.URLEncoder.encode(eventInfo?.teamB ?: "Team B", "UTF-8")
        val sameTeams = isSameTeam(eventInfo?.teamA, eventInfo?.teamB)
        var teamAImg = eventInfo?.teamAFlag ?: ""
        if (teamAImg.isBlank() && sameTeams) {
            teamAImg = eventInfo?.teamBFlag ?: ""
        }
        val teamBImg = if (sameTeams) "" else (eventInfo?.teamBFlag ?: "")
        val eventLogo = eventInfo?.eventBanner ?: ""
        val isLive = isEventLive(channel)
        val isEnded = isEventEnded(channel)
        val time = try {
            eventInfo?.startTime?.let {
                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val displayFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a z", Locale.US)
                dateFormat.parse(it)?.let { d ->
                    java.net.URLEncoder.encode(displayFormat.format(d), "UTF-8")
                } ?: ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
        return buildString {
            append("https://card.cncverse.dpdns.org/?")
            append("title=$title")
            append("&teamA=$teamA")
            if (!sameTeams) append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LIVETVProviderManager.refreshFromFirebase()
        DonationManager.checkAndShow()

        val channels = if (mode == "highlights") {
            LIVETVProviderManager.fetchHighlights()
        } else {
            LIVETVProviderManager.fetchLiveEvents()
        }

        return newHomePageResponse(
            channels.groupBy { it.category ?: "Other" }.map { (category, categoryChannels) ->
                val icon = when (category.lowercase()) {
                    "boxing" -> "🥊"
                    "tennis" -> "🎾"
                    "ice hockey" -> "🏒"
                    "football" -> "⚽"
                    "motorsport" -> "🏎️"
                    "basketball" -> "🏀"
                    "cricket" -> "🏏"
                    else -> "📺"
                }
                val searchResponses = categoryChannels
                    .sortedWith(
                        compareBy<LIVETVChannel> { channel ->
                            when {
                                getEventStatus(channel).contains("🔴") -> 0
                                getEventStatus(channel).contains("🔜") -> 1
                                getEventStatus(channel).contains("✅") -> 2
                                else -> 3
                            }
                        }.thenBy { channel ->
                            try {
                                channel.eventInfo?.startTime?.let {
                                    SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(it)?.time
                                } ?: Long.MAX_VALUE
                            } catch (_: Exception) {
                                Long.MAX_VALUE
                            }
                        }
                    )
                    .map { channel -> channel.toSearchResponse() }
                HomePageList(
                    name = "$icon $category",
                    list = searchResponses,
                    isHorizontalImages = true,
                )
            },
            hasNext = false,
        )
    }

    private fun LIVETVChannel.toSearchResponse(): LiveSearchResponse {
        val displayTitle = createDisplayTitle(this)
        val status = getEventStatus(this)
        val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
        val posterUrl = if (cat == "Custom" && !image.isNullOrBlank()) {
            image
        } else {
            generateMatchCardUrl(this)
        }
        val loadData = LiveEventLoadData(
            channelId = id ?: "",
            title = displayTitle,
            poster = posterUrl,
            category = category,
            eventInfo = eventInfo,
            formats = formats ?: emptyList(),
        )
        return newLiveSearchResponse(
            name = fullTitle,
            url = loadData.toJson(),
            type = TvType.Live,
        ) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = if (mode == "highlights") {
            LIVETVProviderManager.fetchHighlights()
        } else {
            LIVETVProviderManager.fetchLiveEvents()
        }
        return channels
            .filter { channel ->
                val searchText = listOfNotNull(
                    channel.title,
                    channel.eventInfo?.teamA,
                    channel.eventInfo?.teamB,
                    channel.eventInfo?.eventName,
                ).joinToString(" ")
                searchText.contains(query, ignoreCase = true)
            }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)

        val plot = buildString {
            data.eventInfo?.let { info ->
                info.eventName?.let { append("🏆 $it\n") }
                info.startTime?.let { startTime ->
                    try {
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val displayFormat = SimpleDateFormat("MMM dd, yyyy HH:mm z", Locale.US)
                        append("🕐 " + displayFormat.format(dateFormat.parse(startTime)!!) + "\n")
                    } catch (_: Exception) {
                        append("🕐 $startTime\n")
                    }
                }
            }
            if (data.formats.isNotEmpty()) {
                append("\n📡 Available Servers: ${data.formats.size}")
            }
        }

        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = plot.ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)
        val streams = LIVETVProviderManager.fetchChannelStreams(loadData.channelId)
        if (streams.isEmpty()) {
            return false
        }

        streams.forEach { stream ->
            try {
                val serverName = stream.title ?: "Server"

                if (stream.type == "2") {
                    val embedTarget = stream.webLink ?: stream.link
                    if (embedTarget.isNullOrBlank()) {
                        return@forEach
                    }
                    val resolvedUrl = resolveEmbedUrlIfNeeded(embedTarget) ?: return@forEach
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = serverName,
                            url = resolvedUrl,
                            type = INFER_TYPE,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    val rawLink = stream.link ?: return@forEach
                    val (url, headers) = parseStreamLink(rawLink)
                    if (url.isBlank()) {
                        return@forEach
                    }
                    val resolvedUrl = resolveEmbedUrlIfNeeded(url) ?: return@forEach
                    emitLink(callback, serverName, resolvedUrl, headers, stream.api)
                }
            } catch (_: Exception) {
            }
        }
        return true
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        serverName: String,
        url: String,
        headers: Map<String, String>,
        api: String?,
    ) {
        if (url.contains(".mpd")) {
            val drmInfo = api?.takeIf { it.contains(":") }?.split(":")
            if (drmInfo != null && drmInfo.size == 2) {
                val kidB64 = hexToBase64(drmInfo[0])
                val keyB64 = hexToBase64(drmInfo[1])
                if (kidB64 != null && keyB64 != null) {
                    callback.invoke(
                        newDrmExtractorLink(
                            this.name,
                            serverName,
                            url,
                            INFER_TYPE,
                            CLEARKEY_UUID,
                        ) {
                            this.quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) {
                                this.headers = headers
                            }
                            this.key = keyB64
                            this.kid = kidB64
                        }
                    )
                    return
                }
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = serverName,
                    url = url,
                    type = ExtractorLinkType.DASH,
                ) {
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                }
            )
        } else if (url.contains(".m3u8") || url.contains(".m3u")) {
            val finalHeaders = headers.toMutableMap()
            if (!finalHeaders.containsKey("User-Agent")) {
                finalHeaders["User-Agent"] =
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36"
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = serverName,
                    url = url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.quality = Qualities.Unknown.value
                    if (finalHeaders.isNotEmpty()) {
                        this.headers = finalHeaders
                    }
                }
            )
        } else {
            val finalHeaders = headers.toMutableMap()
            if (!finalHeaders.containsKey("User-Agent")) {
                finalHeaders["User-Agent"] =
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36"
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = serverName,
                    url = url,
                    type = INFER_TYPE,
                ) {
                    this.quality = Qualities.Unknown.value
                    if (finalHeaders.isNotEmpty()) {
                        this.headers = finalHeaders
                    }
                }
            )
        }
    }

    private fun hexToBase64(hex: String): String? {
        val normalized = hex.trim().replace("-", "")
        if (normalized.isEmpty() || normalized.length % 2 != 0 || !normalized.matches(Regex("^[0-9a-fA-F]+$"))) {
            return null
        }
        return try {
            val bytes = normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun isDirectStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") ||
            url.contains(".ts") || url.contains(".mkv") || url.contains(".webm")
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String): String? {
        if (isDirectStreamUrl(url)) {
            return url
        }
        return loadEmbedInWebView(url)
    }

    // some servers only expose the stream from inside their player page, run
    // it in a throwaway webview and grab the first media request it fires
    private suspend fun loadEmbedInWebView(embedUrl: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                try {
                    val context = LIVETVLiveEventsProvider.context
                    if (context == null) {
                        continuation.resume(null)
                        return@suspendCoroutine
                    }
                    val webView = WebView(context)
                    val settings = webView.settings
                    settings.javaScriptEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false
                    var urlCaptured = false
                    var capturedUrl: String? = null
                    val bridge = object {
                        @android.webkit.JavascriptInterface
                        fun onStreamUrlFound(url: String) {
                            if (!urlCaptured && url.isNotBlank()) {
                                urlCaptured = true
                                capturedUrl = url
                                Handler(Looper.getMainLooper()).post {
                                    try {
                                        webView.destroy()
                                    } catch (_: Exception) {}
                                    continuation.resume(url)
                                }
                            }
                        }
                    }
                    webView.addJavascriptInterface(bridge, "StreamBridge")
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val requestUrl = request.url.toString()
                            if (isDirectStreamUrl(requestUrl)) {
                                if (!urlCaptured) {
                                    urlCaptured = true
                                    capturedUrl = requestUrl
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            webView.destroy()
                                        } catch (_: Exception) {}
                                        continuation.resume(requestUrl)
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!urlCaptured) {
                                        try {
                                            val jsCode = """
                                                (function() {
                                                    if (typeof playbackURL !== 'undefined' && playbackURL) {
                                                        window.StreamBridge.onStreamUrlFound(playbackURL);
                                                    }
                                                })();
                                            """.trimIndent()
                                            webView.evaluateJavascript(jsCode, null)
                                        } catch (_: Exception) {}
                                    }
                                }, 500)
                            }
                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!urlCaptured) {
                                        try {
                                            webView.destroy()
                                        } catch (_: Exception) {}
                                        continuation.resume(null)
                                    }
                                }, 3000)
                            }
                        }
                    }
                    webView.webChromeClient = WebChromeClient()
                    webView.loadUrl(embedUrl)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!urlCaptured && capturedUrl == null) {
                            try {
                                webView.destroy()
                            } catch (_: Exception) {}
                            try {
                                continuation.resume(null)
                            } catch (_: Exception) {}
                        }
                    }, 30000)
                } catch (_: Exception) {
                    continuation.resume(null)
                }
            }
        }
    }

    // links can carry per stream headers after a pipe, url|Referer=..&Origin=..
    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        if (!link.contains("|")) {
            return Pair(link, headers)
        }
        val parts = link.split("|", limit = 2)
        val url = parts[0]
        if (parts.size > 1) {
            val headerPart = parts[1]
            headerPart.split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    val headerName = when (key.lowercase()) {
                        "user-agent" -> "User-Agent"
                        "referer" -> "Referer"
                        "origin" -> "Origin"
                        "cookie" -> "Cookie"
                        else -> key
                    }
                    headers[headerName] = value
                }
            }
        }
        return Pair(url, headers)
    }
}
