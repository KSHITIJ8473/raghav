package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class TimStreamsProvider : MainAPI() {
    override var mainUrl = "https://timstreams.st"
    override var name = "TimStreams"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://api.vixnuvew.uk/api"
    private val cdnBase = "https://pacquiao.inproviszon.st/"
    private val TAG = "TimStreams"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimStream(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("vip") val vip: Boolean? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimEvent(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("genre") val genre: Int? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("isevent") val isevent: Boolean? = null,
        @JsonProperty("vip") val vip: Boolean? = null,
        @JsonProperty("featured") val featured: Boolean? = null,
        @JsonProperty("streams") val streams: List<TimStream>? = null,
        @JsonProperty("date") val date: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimChannel(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("genre") val genre: Int? = null,
        @JsonProperty("vip") val vip: Boolean? = null,
        @JsonProperty("streams") val streams: List<TimStream>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveUpcomingResponse(
        @JsonProperty("events") val events: List<TimEvent>? = null,
        @JsonProperty("genres") val genres: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelsResponse(
        @JsonProperty("channels") val channels: List<TimChannel>? = null,
        @JsonProperty("genres") val genres: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SettingsResponse(
        @JsonProperty("settings") val settings: Map<String, String>? = null,
        @JsonProperty("genres") val genres: Map<String, String>? = null
    )

    // Load data passed from search → loadLinks
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val title: String,
        val streams: List<TimStream>,
        val posterUrl: String? = null,
        val isUpcoming: Boolean = false
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        try {
            coroutineScope {
                val liveDeferred = async { fetchLiveUpcoming() }
                val channelsDeferred = async { fetchChannels() }

                // Live Events (split into Live Now + Upcoming)
                val liveData = liveDeferred.await()
                if (liveData != null) {
                    val (live, upcoming) = liveData.partition { e ->
                        try {
                            val eventTime = java.time.LocalDateTime.parse(e.time ?: "")
                                .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                            eventTime <= System.currentTimeMillis()
                        } catch (ex: Exception) { true }
                    }
                    if (live.isNotEmpty()) {
                        val items = live.mapNotNull { it.toSearchResponse() }
                        lists.add(HomePageList("🔴 Live Now", items, isHorizontalImages = true))
                    }
                    if (upcoming.isNotEmpty()) {
                        val items = upcoming.mapNotNull { it.toUpcomingSearchResponse() }
                        lists.add(HomePageList("📅 Upcoming Events", items, isHorizontalImages = true))
                    }
                }

                // Live TV Channels
                val channelsData = channelsDeferred.await()
                if (channelsData != null && channelsData.isNotEmpty()) {
                    val items = channelsData.mapNotNull { it.toSearchResponse() }
                    Log.d(TAG, "getMainPage: Live TV -> ${items.size} items")
                    lists.add(HomePageList("📺 Live TV Channels", items, isHorizontalImages = true))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED: ${e.message}")
            Log.e(TAG, "getMainPage error: ${e.stackTraceToString().take(400)}")
        }

        Log.d(TAG, "getMainPage END: ${lists.size} sections, ${lists.sumOf { it.list.size }} total items")
        return newHomePageResponse(lists, hasNext = false)
    }

    private suspend fun fetchLiveUpcoming(): List<TimEvent>? {
        return try {
            val res = app.get("$apiUrl/live-upcoming", timeout = 30_000L)
            Log.d(TAG, "fetchLiveUpcoming: HTTP ${res.code}, size=${res.text.length}")
            parseJson<LiveUpcomingResponse>(res.text).events
        } catch (e: Exception) {
            Log.e(TAG, "fetchLiveUpcoming FAILED: ${e.message}"); null
        }
    }

    private suspend fun fetchChannels(): List<TimChannel>? {
        return try {
            val res = app.get("$apiUrl/channels", timeout = 30_000L)
            Log.d(TAG, "fetchChannels: HTTP ${res.code}, size=${res.text.length}")
            parseJson<ChannelsResponse>(res.text).channels
        } catch (e: Exception) {
            Log.e(TAG, "fetchChannels FAILED: ${e.message}"); null
        }
    }

    // mainPage — single section triggers getMainPage which returns ALL sections at once
    override val mainPage = mainPageOf(
        "$apiUrl/live-upcoming" to "All"
    )

    private fun TimEvent.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val streams = streams ?: return null
        if (streams.isEmpty()) return null
        val loadData = LoadData(title = title, streams = streams, posterUrl = logo)
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun TimEvent.toUpcomingSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val streams = streams ?: return null
        if (streams.isEmpty()) return null
        val displayTitle = "$title [Upcoming: ${time ?: date ?: "TBD"}]"
        Log.d(TAG, "toUpcomingSearchResponse: '$displayTitle' streams=${streams.size}")
        val loadData = LoadData(title = title, streams = streams, posterUrl = logo, isUpcoming = true)
        return newLiveSearchResponse(displayTitle, loadData.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun TimChannel.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val streams = streams ?: return null
        if (streams.isEmpty()) return null
        Log.d(TAG, "toSearchResponse: channel '$title' streams=${streams.size}")
        val loadData = LoadData(title = title, streams = streams, posterUrl = logo)
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search START: query='$query'")
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()

        try {
            // Search events
            val eventsRes = app.get("$apiUrl/live-upcoming", timeout = 30_000L)
            val eventsParsed = parseJson<LiveUpcomingResponse>(eventsRes.text)
            eventsParsed.events?.forEach { e ->
                if (e.name?.contains(query, ignoreCase = true) == true) {
                    e.toSearchResponse()?.let { results.add(it) }
                }
            }

            // Search channels
            val channelsRes = app.get("$apiUrl/channels", timeout = 30_000L)
            val channelsParsed = parseJson<ChannelsResponse>(channelsRes.text)
            channelsParsed.channels?.forEach { c ->
                if (c.name?.contains(query, ignoreCase = true) == true) {
                    c.toSearchResponse()?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED: ${e.message}")
        }

        Log.d(TAG, "search END: '$query' -> ${results.size} results")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load START: url='$url'")
        return try {
            val loadData = parseJson<LoadData>(url)
            Log.d(TAG, "load: title='${loadData.title}' streams=${loadData.streams.size} isUpcoming=${loadData.isUpcoming}")

            newLiveStreamLoadResponse(loadData.title, url, this.name) {
                this.posterUrl = loadData.posterUrl
                this.plot = "${loadData.streams.size} stream sources available"
                this.dataUrl = loadData.toJson()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = try {
            parseJson<LoadData>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: failed to parse LoadData: ${e.message}")
            return false
        }

        if (loadData.streams.isEmpty()) {
            Log.e(TAG, "loadLinks: no streams in LoadData")
            return false
        }

        var found = false
        for (stream in loadData.streams) {
            val streamName = stream.name
            val streamUrl = stream.url

            try {
                when {
                    streamUrl.contains("icelanders.st") -> {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                                script = """document.querySelector('video,[role="button"],.vjs-big-play-button,button,.play-button')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L
                            )
                            val resolvedUrl = app.get(streamUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains(".mp4", ignoreCase = true)) {
                                val embedHost = try {
                                    val uri = android.net.Uri.parse(streamUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) { null }

                                val cookieStr = if (embedHost != null) {
                                    try { android.webkit.CookieManager.getInstance().getCookie(embedHost) ?: "" } catch (e: Exception) { "" }
                                } else { "" }

                                val cdnHost = try {
                                    val uri = android.net.Uri.parse(resolvedUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) { null }

                                val cdnCookies = if (cdnHost != null) {
                                    try { android.webkit.CookieManager.getInstance().getCookie(cdnHost) ?: "" } catch (e: Exception) { "" }
                                } else { "" }

                                val allCookies = listOf(cookieStr, cdnCookies).filter { it.isNotBlank() }.joinToString("; ")

                                val headers = mutableMapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to streamUrl
                                )
                                if (allCookies.isNotBlank()) headers["Cookie"] = allCookies

                                val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - $streamName",
                                        name = "$name - $streamName",
                                        url = resolvedUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                                found = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '$streamName' icelanders.st failed: ${e.message}")
                        }
                    }

                    streamUrl.contains("ritzembeds.pages.dev") || streamUrl.contains("vileembeds.pages.dev") -> {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.m3u8(?:\?|$)""")),
                                script = """document.querySelector('.vjs-big-play-button,.play-button,button,[role=button]')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L
                            )
                            val resolvedUrl = app.get(streamUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                                try {
                                    android.webkit.CookieManager.getInstance().flush()
                                } catch (e: Exception) { }

                                // Extract CF cookies from CookieManager for the CDN domain
                                // The WebView already solved the CF challenge, cookies are in CookieManager
                                val cdnHost = try {
                                    val uri = android.net.Uri.parse(resolvedUrl)
                                    "${uri.scheme}://${uri.host}"
                                } catch (e: Exception) { null }

                                val cookieStr = if (cdnHost != null) {
                                    try {
                                        android.webkit.CookieManager.getInstance().getCookie(cdnHost) ?: ""
                                    } catch (e: Exception) { "" }
                                } else { "" }

                                // Also extract cookies from the ritzembeds domain
                                val ritzCookies = try {
                                    android.webkit.CookieManager.getInstance().getCookie("https://ritzembeds.pages.dev") ?: ""
                                } catch (e: Exception) { "" }

                                // Combine all cookies (cdn + ritzembeds)
                                val allCookies = listOf(cookieStr, ritzCookies)
                                    .filter { it.isNotBlank() }
                                    .joinToString("; ")

                                // Build headers with cookies + Referer (matching what the WebView sent)
                                val headers = mutableMapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to streamUrl,
                                    "Origin" to "https://ritzembeds.pages.dev"
                                )
                                if (allCookies.isNotBlank()) {
                                    headers["Cookie"] = allCookies
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - $streamName",
                                        name = "$name - $streamName",
                                        url = resolvedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                                found = true
                            } else {
                                Log.e(TAG, "loadLinks: '$streamName' WebViewResolver did not intercept .m3u8 (got: $resolvedUrl)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '$streamName' WebViewResolver failed: ${e.message}")
                        }
                    }

                    // luluvdo.com / luluvid.com → built-in extractor
                    streamUrl.contains("luluvdo.com") || streamUrl.contains("luluvid.com") -> {
                        val realUrl = streamUrl.replace("luluvid.com", "luluvdo.com")
                        val loaded = loadExtractor(realUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            found = true
                        } else {
                            Log.e(TAG, "loadLinks: '$streamName' loadExtractor returned false")
                        }
                    }

                    // player.vimeo.com → built-in extractor
                    streamUrl.contains("player.vimeo.com") -> {
                        val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            found = true
                        } else {
                            Log.e(TAG, "loadLinks: '$streamName' Vimeo loadExtractor returned false")
                        }
                    }

                    // timstreams.upn.one → custom player with encrypted API
                    // Use WebViewResolver to load the page, let the JS player init,
                    // and intercept the .m3u8 or .mp4 URL it requests
                    streamUrl.contains("upn.one") -> {
                        try {
                            val resolver = WebViewResolver(
                                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                                script = """document.querySelector('video,[role="button"],.vds-play-button,button')?.click();""",
                                useOkhttp = false,
                                timeout = 30_000L
                            )
                            val resolvedUrl = app.get(streamUrl, referer = "$mainUrl/", interceptor = resolver).url

                            if (resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains(".mp4", ignoreCase = true)) {
                                // Extract cookies from the upn.one domain
                                val upnCookies = try {
                                    android.webkit.CookieManager.getInstance().getCookie("https://timstreams.upn.one") ?: ""
                                } catch (e: Exception) { "" }

                                val headers = mutableMapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                    "Referer" to "https://timstreams.upn.one/"
                                )
                                if (upnCookies.isNotBlank()) {
                                    headers["Cookie"] = upnCookies
                                }

                                val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - $streamName",
                                        name = "$name - $streamName",
                                        url = resolvedUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = headers
                                    }
                                )
                                found = true
                            } else {
                                Log.e(TAG, "loadLinks: '$streamName' upn.one WebViewResolver did not intercept stream URL (got: $resolvedUrl)")
                                // Fallback: try loadExtractor
                                val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                                if (loaded) {
                                    found = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '$streamName' upn.one WebViewResolver failed: ${e.message}")
                            // Fallback: try loadExtractor
                            val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                            if (loaded) {
                                found = true
                            }
                        }
                    }

                    // Direct m3u8/mp4 URLs
                    streamUrl.contains(".m3u8") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - $streamName",
                                name = "$name - $streamName",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        found = true
                    }

                    streamUrl.contains(".mp4") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - $streamName",
                                name = "$name - $streamName",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }

                    // Fallback: try loadExtractor for any other URL
                    else -> {
                        val loaded = loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            found = true
                            Log.d(TAG, "loadLinks: '$streamName' resolved via loadExtractor fallback")
                        } else {
                            Log.e(TAG, "loadLinks: '$streamName' loadExtractor fallback failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: FAILED for stream '$streamName': ${e.message}")
            }
        }

        Log.d(TAG, "loadLinks END: found=$found for '${loadData.title}'")
        return found
    }

    /**
     * Extract the embed ID from a ritzembeds/vileembeds URL.
     * "https://ritzembeds.pages.dev/embed/fox-usa" → "fox-usa"
     */
    private fun extractEmbedId(url: String): String? {
        val embedIndex = url.indexOf("/embed/")
        if (embedIndex < 0) return null
        val afterEmbed = url.substring(embedIndex + 7) // after "/embed/"
        // Take everything up to the next /, ?, #, or end
        val id = afterEmbed.split("/", "?", "#").firstOrNull()
        return id?.takeIf { it.isNotBlank() }
    }
}
